# order-service

Checkout, orders and **shipments**, and a copy of each order's payment status.
Port **8082**. Database: `order_db`. Design: `infrastructure/docs/mvp/05-components/order-service.md`.

## What it talks to

| Other part | How | Why |
|---|---|---|
| product-service | HTTP, `/internal/v1/product/reservations` | Take stock at checkout (and get the real prices), give it back on cancel or return |
| RabbitMQ | Events | Publishes `order.placed`, `order.shipped`, `order.delivered`, `order.returned`, `order.cancelled`. Listens to `payment.collected`, `payment.succeeded`, `payment.cancelled` |
| Object storage | S3 API, private bucket `shipment-documents` | The optional pickup receipt photo of a shipment |
| user-service | Nothing at runtime | It only checks the login cookie with the shared `JWT_SECRET` |

Events are sent **after** the database commit (M19). If RabbitMQ is down at that moment the event is lost and an error is logged;
a reliable outbox can replace this later.

## API

Customer URLs need a login and show only **your own** orders (someone else's order answers 404).
Admin URLs need `ROLE_ADMIN` (401 without login, 403 for a normal user).

| Method | Path | Access | Does |
|---|---|---|---|
| POST | `/api/v1/order/orders` | Login | Checkout (cash on delivery). Prices are never sent by the browser |
| GET | `/api/v1/order/orders` | Login | My orders, newest first |
| GET | `/api/v1/order/orders/{orderNo}` | Owner | One order with items and shipment |
| POST | `/api/v1/order/orders/{orderNo}/cancel` | Owner | Cancel before shipping |
| POST | `/api/v1/order/orders/{orderNo}/received` | Owner | "I received it" (saves the time, changes no status) |
| GET | `/api/v1/order/admin/orders` | Admin | List. Filters: `shipmentStatus`, `paymentStatus`, `q`, `from`, `to`, `overdue`, `page`, `size` |
| GET | `/api/v1/order/admin/orders/summary` | Admin | Dashboard numbers |
| GET | `/api/v1/order/admin/orders/{orderNo}` | Admin | One order with address and phone (packing slip) |
| POST | `/api/v1/order/admin/orders/{orderNo}/cancel` | Admin | Cancel before shipping |
| GET | `/api/v1/order/admin/shipments` | Admin | List. Filters: `courier`, `status` |
| POST | `/api/v1/order/admin/shipments` | Admin | New shipment (multipart: `orderNo`, `courier`, `trackingNumber`, optional file `pickupReceipt`) |
| POST | `/api/v1/order/admin/shipments/delivered` | Admin | Body `{"shipmentIds":[1,2]}` |
| POST | `/api/v1/order/admin/shipments/returned` | Admin | Body `{"shipmentIds":[1],"reason":"REFUSED"}` |
| GET | `/api/v1/order/admin/shipments/{id}/pickup-receipt` | Admin | The private pickup receipt file |

Checkout body:

```json
{
  "lines": [{ "productId": 1, "quantity": 2 }],
  "address": { "phone": "077 123 4567", "addressLine": "12 Galle Road", "city": "Colombo", "district": "Colombo" }
}
```

"delivered" and "returned" first check **all** shipments (nothing changes if one is not `IN_TRANSIT`).
Then each shipment is changed on its own. The answer lists `done` and `failed`, so one problem
(for example product-service down during a return) does not undo the others.

Error codes (shared 7-field JSON): `OUT_OF_STOCK` (with a list in `details`), `INVALID_ORDER_STATUS`,
`TRACKING_NUMBER_TAKEN`, `PRODUCT_SERVICE_UNAVAILABLE`, `INVALID_FILE`, `NOT_FOUND`, `VALIDATION_FAILED`,
`ACCESS_DENIED` and the token codes.

## Status rules

Two separate columns (M4). The allowed moves are in `infrastructure/docs/mvp/09-state-diagrams.md`.

| Action | Needs | Changes | Event |
|---|---|---|---|
| Checkout | stock for every line | `NOT_SHIPPED`, payment `PENDING` | `order.placed` |
| Create shipment | `NOT_SHIPPED`, tracking number unused for that courier | `IN_TRANSIT` | `order.shipped` |
| Delivered | `IN_TRANSIT` | `DELIVERED` | `order.delivered` |
| Returned | `IN_TRANSIT` | `RETURNED`, stock back | `order.returned` |
| Cancel | `NOT_SHIPPED` | `CANCELLED`, stock back | `order.cancelled` |

`payment_status` is changed only by payment events (`PENDING` to `COLLECTED` to `PAID`, or `PENDING` to `CANCELLED`).
Events that arrive twice, or in a wrong order, are ignored safely.

When stock must go back (cancel, return) the call to product-service happens **inside** the transaction that locks the order row.
If product-service is down, nothing changes and the person tries again.

## Settings

| Setting | Env name | Meaning |
|---|---|---|
| `spring.datasource.*` | `DB_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | Its own database |
| `spring.rabbitmq.*` | `SPRING_RABBITMQ_HOST`, `_PORT`, `_USERNAME`, `_PASSWORD` | RabbitMQ |
| `auth.token.secret` | `JWT_SECRET` | Same value as user-service |
| `product-service.base-url` | `PRODUCT_SERVICE_URL` | Where product-service is |
| `storage.s3.*` | `S3_ENDPOINT`, `S3_REGION`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `S3_BUCKET` | Object storage |
| `shop.*` | (in `application.yaml`) | Currency, order number prefix, delivery fee, list of couriers, overdue days, limits |

Profiles: `dev` (default, IntelliJ, helpers on `localhost`), `docker`, `prod`.

## Run and test

```bash
./mvnw test                 # all tests, on H2, no Docker needed
./mvnw spring-boot:run      # needs: docker compose up -d postgres rabbitmq object-storage, and product-service on 8081
```

Check: http://localhost:8082/actuator/health shows `{"status":"UP"}`.
