package com.ecommerce.orderservice.service;

import com.ecommerce.orderservice.client.ProductClient;
import com.ecommerce.orderservice.config.ShipmentProperties;
import com.ecommerce.orderservice.config.ShopProperties;
import com.ecommerce.orderservice.dto.OrderDtos.OrderDetail;
import com.ecommerce.orderservice.dto.OrderDtos.PageResponse;
import com.ecommerce.orderservice.dto.ShipmentDtos.BulkResult;
import com.ecommerce.orderservice.dto.ShipmentDtos.ShipmentSummary;
import com.ecommerce.orderservice.entity.Enums.ReturnReason;
import com.ecommerce.orderservice.entity.Enums.ShipmentState;
import com.ecommerce.orderservice.entity.Enums.ShipmentStatus;
import com.ecommerce.orderservice.entity.Order;
import com.ecommerce.orderservice.entity.Shipment;
import com.ecommerce.orderservice.event.EventPublisher;
import com.ecommerce.orderservice.event.OrderEvents;
import com.ecommerce.orderservice.exception.ApiException;
import com.ecommerce.orderservice.exception.ErrorCode;
import com.ecommerce.orderservice.repository.OrderRepository;
import com.ecommerce.orderservice.repository.ShipmentRepository;
import com.ecommerce.orderservice.service.storage.ObjectStorage;
import com.ecommerce.orderservice.service.storage.StorageException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Shipments: the admin hands a parcel to a courier, then marks it delivered or returned
 * (docs/mvp/06-dynamic/after-order.md, flows 1 to 3; allowed moves in docs/mvp/09-state-diagrams.md).
 *
 * Every change of a shipment also changes the shipment_status of its order and publishes one event.
 * The payment_status is never touched here: only payment events change it (M3).
 */
@Slf4j
@Service
public class ShipmentService {

    /** Letters, digits and . _ / - ; must start with a letter or digit. */
    private static final Pattern TRACKING = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/-]{0,99}$");

    private final OrderRepository orders;
    private final ShipmentRepository shipments;
    private final ProductClient productClient;
    private final ObjectStorage storage;
    private final EventPublisher events;
    private final OrderMapper mapper;
    private final TransactionTemplate tx;
    private final ShopProperties shop;
    private final ShipmentProperties settings;
    private final Paging paging;

    public ShipmentService(OrderRepository orders, ShipmentRepository shipments, ProductClient productClient,
                           ObjectStorage storage, EventPublisher events, OrderMapper mapper, TransactionTemplate tx,
                           ShopProperties shop, ShipmentProperties settings, Paging paging) {
        this.orders = orders;
        this.shipments = shipments;
        this.productClient = productClient;
        this.storage = storage;
        this.events = events;
        this.mapper = mapper;
        this.tx = tx;
        this.shop = shop;
        this.settings = settings;
        this.paging = paging;
    }

    // =====================================================================
    // Create a shipment: the parcel goes to the courier
    // =====================================================================

    /**
     * Order NOT_SHIPPED -> IN_TRANSIT, publishes order.shipped. The pickup receipt photo is optional.
     * Order of work: check everything -> store the photo -> save the shipment (if saving fails the photo is removed again).
     */
    public OrderDetail create(Long adminUserId, String orderNo, String courierInput, String trackingInput,
                              MultipartFile pickupReceipt) {
        String no = orderNo == null ? "" : orderNo.trim();
        if (no.isEmpty()) {
            throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED, "orderNo must not be blank.");
        }
        String courier = canonicalCourier(courierInput);
        String tracking = cleanTracking(trackingInput);
        StoredReceipt receipt = readReceipt(pickupReceipt);

        // Cheap early check, before anything is stored. The real check is repeated under a lock below.
        tx.executeWithoutResult(status -> checkCanShip(findOrder(no), courier, tracking));

        String key = receipt == null ? null : no + "/pickup-" + UUID.randomUUID() + "." + receipt.type().extension();
        if (key != null) {
            try {
                storage.put(key, receipt.bytes(), receipt.type().contentType());
            } catch (StorageException e) {
                log.error("Pickup receipt storage failed", e);
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.INTERNAL_ERROR,
                        "The file storage is not available. Please try again.", List.of());
            }
        }

        try {
            return tx.execute(status -> {
                Order order = orders.findByOrderNoForUpdate(no)
                        .orElseThrow(() -> ApiException.notFound("Order not found."));
                checkCanShip(order, courier, tracking);

                Shipment shipment = new Shipment(courier, tracking, key, adminUserId);
                order.addShipment(shipment);
                order.setShipmentStatus(ShipmentStatus.IN_TRANSIT);
                orders.saveAndFlush(order);

                events.publish(OrderEvents.SHIPPED, OrderEvents.shipped(order, shipment));
                log.info("Order {} was handed to {}", no, courier);
                return mapper.toDetail(order);
            });
        } catch (DataIntegrityViolationException e) {
            removeQuietly(key);
            throw trackingTaken(courier, tracking);
        } catch (RuntimeException e) {
            removeQuietly(key);
            throw e;
        }
    }

    // =====================================================================
    // Delivered / returned: one or many at once
    // =====================================================================

    /**
     * The courier delivered these parcels and now holds the cash. order -> DELIVERED, publishes order.delivered
     * (payment-service then marks the payment COLLECTED).
     * First every shipment is checked (nothing changes if one is not IN_TRANSIT), then each is changed in its own transaction.
     */
    public BulkResult markDelivered(Collection<Long> shipmentIds) {
        List<Target> targets = checkAllInTransit(shipmentIds);
        return process(targets, target -> deliverOne(target.orderNo(), target.shipmentId()));
    }

    /**
     * The courier brought these parcels back. order -> RETURNED, the stock goes back at product-service (M23),
     * publishes order.returned (payment-service then marks the payment CANCELLED).
     */
    public BulkResult markReturned(Collection<Long> shipmentIds, ReturnReason reason) {
        List<Target> targets = checkAllInTransit(shipmentIds);
        return process(targets, target -> returnOne(target.orderNo(), target.shipmentId(), reason));
    }

    private void deliverOne(String orderNo, Long shipmentId) {
        tx.executeWithoutResult(status -> {
            Order order = orders.findByOrderNoForUpdate(orderNo).orElseThrow(() -> ApiException.notFound("Order not found."));
            Shipment shipment = requireInTransit(order, shipmentId);

            shipment.setStatus(ShipmentState.DELIVERED);
            shipment.setDeliveredAt(Instant.now());
            order.setShipmentStatus(ShipmentStatus.DELIVERED);
            orders.save(order);

            events.publish(OrderEvents.DELIVERED, OrderEvents.delivered(order, shipment));
            log.info("Order {} was delivered", orderNo);
        });
    }

    private void returnOne(String orderNo, Long shipmentId, ReturnReason reason) {
        tx.executeWithoutResult(status -> {
            Order order = orders.findByOrderNoForUpdate(orderNo).orElseThrow(() -> ApiException.notFound("Order not found."));
            Shipment shipment = requireInTransit(order, shipmentId);

            // Inside the transaction that holds the order lock: if product-service is down, nothing changes.
            productClient.release(orderNo);

            shipment.setStatus(ShipmentState.RETURNED);
            shipment.setReturnReason(reason);
            shipment.setReturnedAt(Instant.now());
            order.setShipmentStatus(ShipmentStatus.RETURNED);
            orders.save(order);

            events.publish(OrderEvents.RETURNED, OrderEvents.returned(order, shipment));
            log.info("Order {} was returned ({})", orderNo, reason);
        });
    }

    // =====================================================================
    // Read
    // =====================================================================

    public PageResponse<ShipmentSummary> list(String courier, ShipmentState state, Integer page, Integer size) {
        Specification<Shipment> filter = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> all = new ArrayList<>();
            if (courier != null && !courier.isBlank()) {
                all.add(cb.equal(cb.lower(root.get("courier")), courier.trim().toLowerCase()));
            }
            if (state != null) {
                all.add(cb.equal(root.get("status"), state));
            }
            boolean isCountQuery = Long.class.equals(query.getResultType()) || long.class.equals(query.getResultType());
            if (!isCountQuery) {
                query.orderBy(cb.desc(root.get("shippedAt")), cb.desc(root.get("id")));
            }
            return cb.and(all.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        return tx.execute(status -> {
            var result = shipments.findAll(filter, paging.of(page, size, Sort.unsorted()));
            return paging.map(result, this::toSummary);
        });
    }

    /** The pickup receipt file of a shipment (admin only). 404 when there is none. */
    public ObjectStorage.StoredFile pickupReceipt(Long shipmentId) {
        String key = tx.execute(status -> shipments.findById(shipmentId)
                .map(Shipment::getPickupReceiptKey)
                .orElseThrow(() -> ApiException.notFound("Shipment not found.")));
        if (key == null) {
            throw ApiException.notFound("This shipment has no pickup receipt.");
        }
        try {
            return storage.get(key).orElseThrow(() -> ApiException.notFound("The pickup receipt file was not found."));
        } catch (StorageException e) {
            log.error("Could not read the pickup receipt of shipment {}", shipmentId, e);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.INTERNAL_ERROR,
                    "The file storage is not available. Please try again.", List.of());
        }
    }

    // =====================================================================
    // helpers
    // =====================================================================

    /** What is needed to change one shipment later, read once at the start. */
    private record Target(Long shipmentId, String orderNo) {
    }

    private record StoredReceipt(byte[] bytes, ReceiptFileType type) {
    }

    /** All or nothing check before any change: every id must exist and be IN_TRANSIT. */
    private List<Target> checkAllInTransit(Collection<Long> ids) {
        return tx.execute(status -> {
            List<Shipment> found = shipments.findByIdIn(ids);
            List<String> problems = new ArrayList<>();

            ids.stream().filter(id -> found.stream().noneMatch(s -> s.getId().equals(id)))
                    .forEach(id -> problems.add("Shipment " + id + " does not exist."));
            found.stream().filter(s -> s.getStatus() != ShipmentState.IN_TRANSIT)
                    .forEach(s -> problems.add(describe(s) + " is " + s.getStatus() + ", not IN_TRANSIT."));

            if (!problems.isEmpty()) {
                throw ApiException.conflict(ErrorCode.INVALID_ORDER_STATUS,
                        "Nothing was changed, because some shipments cannot be updated.", problems);
            }
            return found.stream().map(s -> new Target(s.getId(), s.getOrder().getOrderNo())).toList();
        });
    }

    private BulkResult process(List<Target> targets, java.util.function.Consumer<Target> action) {
        List<BulkResult.Done> done = new ArrayList<>();
        List<BulkResult.Failure> failed = new ArrayList<>();

        for (Target target : targets) {
            try {
                action.accept(target);
                done.add(new BulkResult.Done(target.shipmentId(), target.orderNo()));
            } catch (ApiException e) {
                failed.add(new BulkResult.Failure(target.shipmentId(), target.orderNo(), e.getCode().name(), e.getMessage()));
            } catch (RuntimeException e) {
                log.error("Could not update shipment {} of order {}", target.shipmentId(), target.orderNo(), e);
                failed.add(new BulkResult.Failure(target.shipmentId(), target.orderNo(),
                        ErrorCode.INTERNAL_ERROR.name(), ErrorCode.INTERNAL_ERROR.getMessage()));
            }
        }
        return new BulkResult(done, failed);
    }

    private Shipment requireInTransit(Order order, Long shipmentId) {
        Shipment shipment = order.getShipment();
        if (shipment == null || !shipment.getId().equals(shipmentId)) {
            throw ApiException.notFound("Shipment not found.");
        }
        if (shipment.getStatus() != ShipmentState.IN_TRANSIT) {
            throw ApiException.conflict(ErrorCode.INVALID_ORDER_STATUS,
                    describe(shipment) + " is already " + shipment.getStatus() + ".");
        }
        return shipment;
    }

    private void checkCanShip(Order order, String courier, String tracking) {
        if (order.getShipmentStatus() != ShipmentStatus.NOT_SHIPPED || !order.getShipments().isEmpty()) {
            throw ApiException.conflict(ErrorCode.INVALID_ORDER_STATUS,
                    "Only an order that is not shipped yet can get a shipment. " + order.getOrderNo()
                            + " is " + order.getShipmentStatus() + ".");
        }
        if (shipments.existsByCourierIgnoreCaseAndTrackingNumber(courier, tracking)) {
            throw trackingTaken(courier, tracking);
        }
    }

    private Order findOrder(String orderNo) {
        return orders.findByOrderNo(orderNo).orElseThrow(() -> ApiException.notFound("Order not found."));
    }

    private String canonicalCourier(String input) {
        Optional<String> match = input == null ? Optional.empty()
                : shop.couriers().stream().filter(name -> name.equalsIgnoreCase(input.trim())).findFirst();
        return match.orElseThrow(() -> ApiException.badRequest(ErrorCode.VALIDATION_FAILED,
                "courier must be one of: " + String.join(", ", shop.couriers()) + "."));
    }

    private String cleanTracking(String input) {
        String tracking = input == null ? "" : input.trim();
        if (!TRACKING.matcher(tracking).matches()) {
            throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED,
                    "trackingNumber may have letters, digits and . _ / - only (at most 100 characters).");
        }
        return tracking;
    }

    private StoredReceipt readReceipt(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        long max = settings.pickupReceipt().maxSizeBytes();
        if (file.getSize() > max) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, ErrorCode.INVALID_FILE,
                    "The file is too big. The limit is " + max / (1024 * 1024) + " MB.", List.of());
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest(ErrorCode.INVALID_FILE, "The file could not be read.");
        }
        ReceiptFileType type = ReceiptFileType.detect(bytes).orElseThrow(() ->
                ApiException.badRequest(ErrorCode.INVALID_FILE, "Only JPEG, PNG, WebP or PDF files are accepted."));
        return new StoredReceipt(bytes, type);
    }

    private ShipmentSummary toSummary(Shipment s) {
        Order order = s.getOrder();
        return new ShipmentSummary(s.getId(), order.getOrderNo(), order.getCustomerName(), s.getCourier(),
                s.getTrackingNumber(), s.getStatus(), s.getShippedAt(), s.getDeliveredAt(), s.getReturnedAt(),
                s.getReturnReason() == null ? null : s.getReturnReason().name(), s.getPickupReceiptKey() != null,
                order.getTotal(), order.getCurrency(), order.getPaymentStatus());
    }

    private String describe(Shipment s) {
        return s.getTrackingNumber() + " (order " + s.getOrder().getOrderNo() + ")";
    }

    private ApiException trackingTaken(String courier, String tracking) {
        return ApiException.conflict(ErrorCode.TRACKING_NUMBER_TAKEN,
                courier + " already has a shipment with tracking number " + tracking + ".");
    }

    /** A file that cannot be removed is only wasted space. It must not fail the request. */
    private void removeQuietly(String key) {
        if (key == null) {
            return;
        }
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not remove stored file '{}'. It stays as an unused file.", key);
        }
    }
}
