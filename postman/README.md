# E2E thủ công bằng Postman — UC-01 … UC-09

File `RHL-E2E.postman_collection.json` chứa toàn bộ flow, gọi qua **api-gateway `http://localhost:8080`**
(WebSocket gọi thẳng **realtime-gateway `ws://localhost:8085/ws`**). Mỗi request có test script tự kiểm tra
kết quả và tự lưu id/token vào biến collection cho request sau.

Collection được sinh từ `gen_collection.py`: muốn thêm/sửa request thì sửa script rồi chạy
`python postman/gen_collection.py`, đừng sửa tay file JSON.

## 1. Chuẩn bị

1. Docker Desktop đang chạy, `docker compose up -d` (Postgres 15432, Redis 16379, Kafka 19092, MinIO 19000).
2. Bật đủ 7 service: user 8081, location 8082, trip 8083, pricing 8084, realtime 8085, payment 8086, gateway 8080.
   - user-service cần `BOOTSTRAP_ADMIN_EMAIL=admin@rhl.local`, `BOOTSTRAP_ADMIN_PASSWORD=admin-password-123`
     (chỉ cần ở lần chạy đầu trên DB mới; nếu đã có admin khác thì sửa biến `adminIdentifier`/`adminPassword`).
   - Mỗi lần restart user-service phải restart cả gateway, location, trip, pricing, payment, realtime
     (khoá JWT dev sinh lại với cùng `kid` → các service khác cache khoá cũ → 401).
3. Postman → **Import** file collection. Biến nằm ở tab *Variables* của collection, không cần environment.

| Biến | Mặc định | Ghi chú |
|---|---|---|
| `baseUrl` | `http://localhost:8080` | gateway |
| `wsUrl` | `ws://localhost:8085/ws` | realtime-gateway |
| `adminIdentifier` / `adminPassword` | `admin@rhl.local` / `admin-password-123` | đóng vai reviewer, support, finance |
| `password` | `Passw0rd!E2e` | mật khẩu của mọi tài khoản E2E |
| `webhookSecret` | `dev-only-webhook-secret-change-me` | = `PAYMENT_WEBHOOK_SECRET` của payment-service |
| `otpDriverA` / `otpDriverB` | *(trống)* | điền tay ở UC-01 |

## 2. Cách chạy

- Chạy **theo thứ tự thư mục**: `00` → `UC-01` → … → `UC-09`. Các UC sau dùng tài khoản/xe/trip của UC trước
  (UC-04 tiếp trip của UC-03, UC-08 tiếp trip `wsTripId` của UC-07).
- **UC-01 chạy tay từng request** vì có 2 bước OTP: sau request *“Gửi mã xác minh email”*, mở log user-service,
  tìm dòng `DEV NOTIFICATION EMAIL to ... code 123456`, điền vào `otpDriverA` (hoặc `otpDriverB`), rồi chạy tiếp.
- Từ UC-02 trở đi dùng **Collection Runner** cho từng thư mục được (bỏ chọn request có nhãn `[THỦ CÔNG]`).
  Một số request tự chờ (2–3 s chờ dispatch/payment, 17 s chờ offer hết hạn, 35 s chờ `NO_DRIVER`).
- Nếu chạy tay, **đừng để quá 30 s** giữa *“Tài xế gửi vị trí”* và *“Khách đặt chuyến”* (vị trí hết hạn → không
  ghép được), và **nhận offer trong 15 s**.
- Mỗi lượt chạy tạo tài khoản mới (`...-<runId>@e2e.local`), nên chạy lại bao nhiêu lần cũng được. Muốn chạy lại
  từ giữa (ví dụ UC-05) thì token/id cũ vẫn còn trong biến; token hết hạn sau 15 phút → chạy lại các request
  *Đăng nhập* trong `00`.
- Request có nhãn `[NEG]` là ca âm: test xanh nghĩa là hệ thống **từ chối đúng**.
- user-service chỉ cho **10 lượt đăng ký/giờ/IP**, mà thư mục `00` dùng 7 lượt (kể cả 2 ca âm). Chạy lại trong
  cùng một giờ sẽ gặp `429`. Ở máy dev, xoá bộ đếm:
  `docker exec rhl-redis-1 redis-cli --scan --pattern 'rl:user:register:*' | xargs docker exec rhl-redis-1 redis-cli del`
- Gửi lặp `cancel` / `complete` / `accept` trả lại **đúng trip đó với 200** (idempotent), không phải 409.

## 3. Flow từng UC

### 00. Chuẩn bị tài khoản
Đăng nhập admin → đăng ký khách 1, khách 2 (người ngoài cuộc), tài xế A, tài xế B → đăng nhập từng người,
lưu token + id. Ca âm: đăng ký trùng email (409), tự đăng ký vai trò `ADMINISTRATOR` (bị chặn).

### UC-01 Đăng ký & xét duyệt tài xế
| Bước | Kỳ vọng |
|---|---|
| A: tạo hồ sơ → thêm xe máy → (tuỳ chọn) upload ảnh → nộp 4 giấy tờ | 201, hồ sơ `DRAFT` |
| A: gửi duyệt khi chưa xác minh email | **bị chặn** |
| A: gửi mã email → đọc log → xác nhận | `emailVerified = true` |
| A: gửi duyệt | `PENDING_REVIEW`, `missingRequirements` rỗng |
| B: như A nhưng nộp **bằng lái hết hạn** | hồ sơ báo thiếu, gửi duyệt **bị chặn**; nộp lại bằng còn hạn → gửi được |
| Khách xem hàng chờ duyệt | 403 |
| Reviewer: hàng chờ có A, B; chi tiết A đủ 4 giấy tờ | 200 |
| Duyệt A với `profileVersion` sai | conflict |
| Duyệt A; từ chối B có lý do → B thấy `REJECTED` → gửi lại (version tăng) → duyệt B | lịch sử B có `REJECT` và `APPROVE` |
| A tải file giấy tờ của B; khách tải qua API reviewer | 404 / 403 |

### UC-02 Tạo báo giá
Báo giá RIDE: tổng > 0, tròn 1000, VND, có `ruleVersion`, `expiresAt`, surge → đọc lại ra **cùng tổng**
(tái lập được). Ca âm: khách 2 đọc hoặc đặt bằng quote của khách 1, tài xế xin báo giá (403), lat = 91 (400).
Báo giá DELIVERY; admin xem bảng giá. `[THỦ CÔNG]`: đợi > 5 phút rồi đặt bằng quote cũ → bị chặn.

### UC-03 Đặt chuyến & ghép tài xế
A online → gửi vị trí gần điểm đón → báo giá → đặt chuyến (`Idempotency-Key`) → **gửi lại cùng key → cùng
trip** → cùng key, body khác → `409 IDEMPOTENCY_KEY_REUSED` → A thấy offer → B nhận offer của A (bị chặn)
→ A nhận → nhận lặp (cùng trip) → khách thấy `pickupCode`, tài xế **không** thấy → A là `BUSY` → khách 2 đặt
chuyến khác → **A không nhận offer thứ hai** → dọn dẹp (khách 2 huỷ).

### UC-04 Theo dõi & hoàn tất chuyến chở khách
`arrive` sai thứ tự (409) → `start-pickup` → gửi vị trí → `arrive` → `start` với mã sai (bị chặn) → `start`
với mã đúng → khách bấm hoàn tất (403) → tài xế `complete` → hoàn tất lặp (200, cùng trip) → lịch sử đúng thứ tự
`ACCEPTED → PICKING_UP → ARRIVED → IN_TRIP → COMPLETED` → **đúng 1 payment `TRIP_FARE` `SUCCEEDED`
= giá báo** → ví tài xế có dòng cho trip, không trùng → gọi lại vẫn 1 payment → khách 2 xem payment (404).

Phần realtime (độ trễ vị trí): làm song song theo mục 4, bước 1–3, trước khi chạy `start-pickup`.

### UC-05 Hoàn tất giao hàng
Đặt DELIVERY thiếu người nhận (bị chặn) → đặt có `delivery{...}` → A nhận → khách thấy người nhận + `pickupCode`
+ `deliveryCode`; tài xế thấy người nhận nhưng **không thấy mã**; khách 2 xem trip (404) → đón → lấy hàng
bằng mã đón → giao không mã / mã sai (bị chặn) → giao bằng mã đúng `COMPLETED` → staff xem có `deliveryProof`,
`deliveryCodeFailures ≥ 1` → **đúng 1 payment**.

### UC-06 Cạnh tranh chấp nhận chuyến
A và B cùng online, cùng vị trí → đặt chuyến → **đúng một** tài xế có offer → tài xế kia nhận offer đó
(bị chặn) → người có offer từ chối → nhận lại offer đã từ chối (409) → offer chuyển sang tài xế còn lại →
nhận, nhận lặp (cùng trip) → huỷ dọn dẹp → **cả A, B về `AVAILABLE`** (không kẹt `OFFERED`/`BUSY`).
Lượt 2: để offer **hết hạn** (17 s) → nhận → `409 OFFER_EXPIRED` → offer chuyển sang người kia → nhận → dọn dẹp.

> Bắn đồng thời 100 accept không làm được bằng Postman (runner chạy tuần tự). Ca đó đã có
> `TripServiceIT` (10 luồng accept cùng lúc → đúng 1 thắng).

### UC-07 Mất & khôi phục realtime
REST: gửi vị trí seq N → gửi lại N (`DUPLICATE`) → gửi N-5000 (bị bỏ qua, cũng trả `DUPLICATE`) → seq N+1 nhưng
giờ thiết bị lùi 5 s (`OUT_OF_ORDER`) → `GET /locations/me` vẫn là N. Sau đó tạo trip `wsTripId` (A nhận, để ở `ACCEPTED`) và làm phần WebSocket ở mục 4. Request cuối là
snapshot `GET /trips/{wsTripId}` sau khi kết nối lại.

### UC-08 Hủy chuyến có phí
- **No-show**: trip `wsTripId` → đón → đến nơi → xem trước phí `CUSTOMER_NO_SHOW` (> 0, có `ruleVersion`) →
  tài xế huỷ → huỷ lặp (200, cùng trip) → **đúng 1 payment `CANCELLATION_FEE` = phí xem trước**.
- **Miễn phí**: trip mới, khách xem phí `CHANGED_MIND` trong 120 s đầu → 0 → huỷ → **0 payment**.
- **Staff**: huỷ với ghi chú < 10 ký tự (bị chặn) → huỷ có ghi chú → `cancelledBy = STAFF`, chi tiết có `cancelNote`.
- **Không thu/hoàn nhiều lần**: finance hoàn 10 000 cho payment UC-04 → gửi lặp cùng key (cùng refund) →
  cùng key khác số tiền (409) → hoàn vượt số đã thu (bị chặn) → payment `PARTIALLY_REFUNDED`,
  `refundedAmount = 10000` → provider gửi lại callback `refund.succeeded` có chữ ký HMAC đúng (không áp dụng lại)
  → replay cùng `eventId` (`DUPLICATE`) → chữ ký sai (401) → `refundedAmount` vẫn 10 000 → điều chỉnh ví
  `REFUND_CLAWBACK -8000` hai lần cùng key → ví chỉ có **1** dòng -8000.

### UC-09 Vị trí tài xế
B online nhưng **không gửi vị trí**. A: `CURRENT` → cùng seq `DUPLICATE` → seq nhỏ hơn `DUPLICATE`
(vị trí không lùi) → seq mới, giờ thiết bị lùi `OUT_OF_ORDER` → bản tin muộn 10 phút `BACKFILL` (chỉ vào lịch sử)
→ vị trí hiện tại vẫn là N → độ chính xác 120 m `LOW_ACCURACY` → nhảy ra Hà Nội `SUSPICIOUS` → timestamp lệch 1 giờ
(bị chặn) → khách gửi / đọc vị trí (403) → A offline → A gửi vị trí (bị chặn) → khách đặt chuyến → chờ 35 s
→ **`NO_DRIVER`**, B không nhận offer nào (A offline, B có vị trí quá 30 s nên không vào matching) → B offline.

> Tải 100 tài xế × 3 s và đo p95 ≤ 500 ms cần script tải; README gốc dự kiến `tools/driver-simulator`
> nhưng thư mục này chưa có. Postman không làm được phần này.

## 4. WebSocket (UC-04, UC-07) — làm tay

Postman: **New → WebSocket**. Mỗi message là JSON; `messageId` dùng `{{$guid}}`, `sentAt` dùng `{{$isoTimestamp}}`.
Session bị đóng (4408) nếu **45 s** không có message nào từ client → gửi `PING` định kỳ.

```json
{ "messageId": "{{$guid}}", "type": "PING", "version": 1, "sentAt": "{{$isoTimestamp}}", "data": {} }
```

1. **Khách** kết nối `{{wsUrl}}?access_token={{customerToken}}` → nhận `SESSION_READY`.
2. Khách gửi:
   ```json
   { "messageId": "{{$guid}}", "type": "SUBSCRIBE_TRIP", "version": 1, "sentAt": "{{$isoTimestamp}}",
     "data": { "tripId": "{{wsTripId}}" } }
   ```
   → `TRIP_SUBSCRIBED` (có `status`, `driverId`).
3. **Tài xế A** mở tab WS thứ hai `{{wsUrl}}?access_token={{driverAToken}}` và gửi (tăng `sequence` mỗi lần,
   cách nhau ≥ 1 s):
   ```json
   { "messageId": "{{$guid}}", "type": "DRIVER_LOCATION_UPDATED", "version": 1, "sentAt": "{{$isoTimestamp}}",
     "sequence": <lastSeq + 1>,
     "data": { "latitude": 10.7731, "longitude": 106.6986, "accuracyMeters": 8, "headingDegrees": 90,
               "speedMetersPerSecond": 5, "deviceTimestamp": "{{$isoTimestamp}}" } }
   ```
   → tab khách nhận `TRIP_DRIVER_LOCATION`. So `serverTimestamp` với giờ nhận để ước lượng độ trễ (mục tiêu ≤ 500 ms).
   `sequence`: lấy giá trị biến collection `lastSeq` (seq của lần gửi REST gần nhất) rồi cộng 1, 2, 3… cho
   từng bản tin. **Đừng dùng số quá lớn**: seq REST là `Date.now()` (ms), nếu WS vượt lên trước thì các lần gửi
   REST sau (UC-08, UC-09) sẽ thành `OUT_OF_ORDER`.
4. Gửi 2 bản tin cách nhau < 1 s → `ERROR` `RATE_LIMIT_EXCEEDED`.
5. Gửi lại một `sequence` cũ → khách **không** nhận thêm `TRIP_DRIVER_LOCATION` (bản tin cũ bị bỏ).
6. **Mất kết nối**: ngắt tab khách → chạy REST `start-pickup` cho `wsTripId` (UC-08 bước đầu, hoặc gọi tay)
   → kết nối lại → `SUBSCRIBE_TRIP` → `TRIP_SUBSCRIBED.status = PICKING_UP` + chạy request *Snapshot* →
   trạng thái mới nhất, không mất. Event có `aggregateVersion` ≤ `version` đã có thì client bỏ qua.
7. **Không lộ vị trí chéo**: kết nối bằng `{{customer2Token}}`, `SUBSCRIBE_TRIP` trip `wsTripId` → `ERROR`,
   không nhận vị trí.
8. Sau khi trip kết thúc (UC-08 no-show), tab khách nhận `TRIP_UNSUBSCRIBED` (`TRIP_ENDED`) sau ~2 phút.
9. (Tuỳ chọn) gọi `POST /api/v1/auth/logout` bằng token đang dùng → session đóng mã **4401**.

> Nếu ở bước 6 bạn tự gọi `start-pickup` thì trong UC-08 bỏ qua request *“[No-show] Tài xế đi đón”*.

## 5. Khi một bước đỏ

| Hiện tượng | Nguyên nhân thường gặp |
|---|---|
| 401 ở mọi request | token hết hạn (15 phút) → chạy lại *Đăng nhập*; hoặc user-service vừa restart → restart các service còn lại |
| 503 `DEPENDENCY_UNAVAILABLE` | service đích chưa chạy |
| *“Có offer cho trip”* đỏ | tài xế chưa online, vị trí quá 30 s, hoặc tài xế đang bận trip khác |
| Payment chưa có | Kafka chậm → chạy lại request (đã chờ 3 s) |
| OTP sai / hết hạn | mã sống 10 phút, gửi lại sau 60 s, tối đa 5 lần/giờ |
