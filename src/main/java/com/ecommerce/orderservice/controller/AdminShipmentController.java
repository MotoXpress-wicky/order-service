package com.ecommerce.orderservice.controller;

import com.ecommerce.orderservice.dto.CurrentUser;
import com.ecommerce.orderservice.dto.OrderDtos.OrderDetail;
import com.ecommerce.orderservice.dto.OrderDtos.PageResponse;
import com.ecommerce.orderservice.dto.ShipmentDtos.BulkResult;
import com.ecommerce.orderservice.dto.ShipmentDtos.DeliveredRequest;
import com.ecommerce.orderservice.dto.ShipmentDtos.ReturnedRequest;
import com.ecommerce.orderservice.dto.ShipmentDtos.ShipmentSummary;
import com.ecommerce.orderservice.entity.Enums.ShipmentState;
import com.ecommerce.orderservice.service.ShipmentService;
import com.ecommerce.orderservice.service.storage.ObjectStorage.StoredFile;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Admin: shipments. SecurityConfig lets only ROLE_ADMIN reach /admin/**. */
@RestController
@RequestMapping("/api/v1/order/admin/shipments")
public class AdminShipmentController {

    private final ShipmentService service;

    public AdminShipmentController(ShipmentService service) {
        this.service = service;
    }

    /** Filters: courier, status, page, size. Newest first. */
    @GetMapping
    public PageResponse<ShipmentSummary> list(@RequestParam(required = false) String courier,
                                              @RequestParam(required = false) ShipmentState status,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size) {
        return service.list(courier, status, page, size);
    }

    /**
     * New shipment. A multipart form with the fields orderNo, courier, trackingNumber and an optional file
     * "pickupReceipt" (photo or PDF).
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public OrderDetail create(@AuthenticationPrincipal CurrentUser admin,
                              @RequestParam String orderNo,
                              @RequestParam String courier,
                              @RequestParam String trackingNumber,
                              @RequestPart(name = "pickupReceipt", required = false) MultipartFile pickupReceipt) {
        return service.create(admin.id(), orderNo, courier, trackingNumber, pickupReceipt);
    }

    @PostMapping("/delivered")
    public BulkResult delivered(@Valid @RequestBody DeliveredRequest request) {
        return service.markDelivered(request.shipmentIds());
    }

    @PostMapping("/returned")
    public BulkResult returned(@Valid @RequestBody ReturnedRequest request) {
        return service.markReturned(request.shipmentIds(), request.reason());
    }

    /** The pickup receipt file. It is private, so it only comes through this admin URL. */
    @GetMapping("/{id}/pickup-receipt")
    public ResponseEntity<byte[]> pickupReceipt(@PathVariable Long id) {
        StoredFile file = service.pickupReceipt(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType() == null ? "application/octet-stream" : file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(file.content());
    }
}
