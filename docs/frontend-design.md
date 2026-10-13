# Thiết kế frontend — Ride-Hailing & Logistics

Tài liệu thiết kế cho ứng dụng web React phục vụ ba nhóm người dùng: **khách hàng**, **tài xế** và
**nhân viên / quản trị**. Chưa có code; tài liệu này chốt màn hình, luồng, cấu trúc thư mục và thư viện
để bắt đầu viết.

Mọi endpoint nêu ở đây đều đã có trong backend hiện tại (gọi qua api-gateway `:8080`, WebSocket gọi thẳng
realtime-gateway `:8085/ws`). Những chỗ backend chưa có được gom ở [mục 10](#10-khoảng-trống-phía-backend).

## 1. Phạm vi

| Nhóm | Thiết bị chính | Việc phải làm được |
|---|---|---|
| Khách hàng | Điện thoại | Đăng ký, lấy báo giá, đặt chuyến / giao hàng, theo dõi tài xế, huỷ, xem thanh toán và lịch sử |
| Tài xế | Điện thoại | Hoàn thiện hồ sơ, bật online, nhận / từ chối offer, chạy chuyến, xem ví |
| Nhân viên | Máy tính | Duyệt hồ sơ (Reviewer), tra cứu chuyến (Support), thanh toán / hoàn tiền / ví (Finance), bảng giá và phân quyền (Administrator) |

Ngoài phạm vi, theo README §2: đặt lịch trước, nhiều điểm dừng, coupon, chat, đa ngôn ngữ, rút tiền khỏi ví.
Giao diện chỉ có tiếng Việt, tiền VND, giờ hiển thị theo múi giờ máy người dùng (server trả UTC).

## 2. Công nghệ

Chọn bản ổn định mới nhất của từng thư viện tại thời điểm khởi tạo dự án, và kiểm tra vòng đời hỗ trợ
trước khi chốt.

| Hạng mục | Lựa chọn | Lý do |
|---|---|---|
| Ngôn ngữ | TypeScript (strict) | Hợp đồng API nhiều trường; bắt lỗi kiểu ngay lúc viết |
| Build | Vite | Khởi động nhanh, cấu hình ít; không cần SSR vì mọi trang đều sau đăng nhập |
| UI | React | Theo yêu cầu |
| Điều hướng | React Router | Route lồng nhau theo vai trò, lazy-load từng khu vực |
| Dữ liệu server | TanStack Query | Cache, tải lại, trạng thái loading/lỗi; event WebSocket chỉ cần `invalidate` hoặc ghi vào cache |
| Trạng thái client | Zustand | Nhỏ gọn cho phiên đăng nhập, trạng thái kết nối, offer đang chờ |
| Form | React Hook Form + Zod | Kiểm tra dữ liệu phía client khớp ràng buộc server (ví dụ lat −90…90, mã 6 số) |
| Giao diện | Tailwind CSS + shadcn/ui (Radix) | Component có sẵn khả năng truy cập, sở hữu mã nguồn, dễ đổi theme |
| Bản đồ | Leaflet + react-leaflet, nền OpenStreetMap | Miễn phí, không cần khoá API |
| Realtime | WebSocket gốc của trình duyệt, bọc trong một lớp riêng | Giao thức đã tự định nghĩa (envelope, PING, AUTH); không cần STOMP hay socket.io |
| Kiểm thử | Vitest + Testing Library + MSW; Playwright cho luồng chính | MSW giả API theo hợp đồng; Playwright chạy với backend thật ở local |
| Chất lượng | ESLint + Prettier | Thêm job vào workflow CI hiện có |

Không dùng Redux (thừa so với nhu cầu) và không dùng Next.js (không có trang công khai cần SEO).

## 3. Kiến trúc tổng thể

**Một ứng dụng Vite, ba khu vực theo vai trò**, tách bundle bằng lazy route. Ba khu vực dùng chung lớp API,
lớp realtime, phiên đăng nhập và bộ component; tách thành ba ứng dụng riêng lúc này chỉ nhân ba phần cấu hình.

```
/                 → trang đăng nhập / đăng ký (chưa đăng nhập)
/app/*            → khách hàng      (ROLE_CUSTOMER)   bố cục di động
/driver/*         → tài xế          (ROLE_DRIVER)     bố cục di động
/admin/*          → nhân viên       (REVIEWER, SUPPORT_STAFF, FINANCE_STAFF, ADMINISTRATOR)  bố cục máy tính
```

Sau khi đăng nhập, ứng dụng đọc `roles` từ `GET /api/v1/users/me` và chuyển tới khu vực tương ứng. Route
guard chỉ để điều hướng cho tiện; quyền thật luôn do server quyết định (403 / 404).

Khu vực khách và tài xế thiết kế **mobile-first** (khung nội dung tối đa ~480 px, canh giữa trên màn hình
lớn), nút bấm chính nằm trong tầm ngón cái ở cạnh dưới. Khu vực quản trị dùng thanh bên + bảng dữ liệu.

## 4. Màn hình

### 4.1. Dùng chung (chưa đăng nhập)

| Route | Màn hình | API |
|---|---|---|
| `/login` | Đăng nhập bằng email hoặc SĐT | `POST /auth/login` |
| `/register` | Đăng ký, chọn Khách hàng hoặc Tài xế | `POST /auth/register` |
| `/forgot-password` | Nhập email/SĐT → nhập mã 6 số + mật khẩu mới | `POST /auth/password-reset`, `…/confirm` |
| `/verify/:channel` | Xác minh email / SĐT bằng mã, có đếm ngược gửi lại | `POST /users/me/contacts/{channel}/verification`, `…/confirm` |

Trang quên mật khẩu luôn hiện cùng một thông báo dù tài khoản có tồn tại hay không, đúng như server.

### 4.2. Khách hàng (`/app`)

Thanh điều hướng dưới: **Đặt xe · Hoạt động · Tài khoản**.

| Route | Màn hình | Nội dung chính | API / realtime |
|---|---|---|---|
| `/app` | Trang chủ đặt xe | Bản đồ toàn màn hình; tấm trượt dưới để chọn dịch vụ (Chở khách / Giao hàng), điểm đón, điểm đến | — |
| `/app/quote` | Báo giá | Tuyến trên bản đồ, quãng đường, thời gian, bảng tách giá, hệ số surge, đồng hồ hết hạn 5 phút; với Giao hàng có form người nhận và gói hàng | `POST /quotes` |
| `/app/trips/:id` | Chuyến đang chạy | Một màn hình đổi nội dung theo trạng thái (xem bảng dưới) | `GET /trips/{id}`, WebSocket |
| `/app/trips/:id/receipt` | Hoá đơn | Giá, trạng thái thanh toán, nút thử lại khi thanh toán lỗi, danh sách hoàn tiền | `GET /payments?tripId=`, `POST /payments/{id}/retry`, `GET /payments/{id}/refunds` |
| `/app/activity` | Lịch sử chuyến | Danh sách phân trang theo con trỏ `before` | `GET /trips` |
| `/app/activity/:id` | Chi tiết chuyến cũ | Thông tin chuyến + dòng thời gian trạng thái | `GET /trips/{id}`, `GET /trips/{id}/history` |
| `/app/account` | Tài khoản | Thông tin, trạng thái xác minh email / SĐT, đăng xuất | `GET /users/me`, `POST /auth/logout` |

Màn hình **Chuyến đang chạy** theo trạng thái:

| Trạng thái | Khách thấy | Hành động |
|---|---|---|
| `CREATED`, `MATCHING` | Hiệu ứng đang tìm tài xế, đếm tới hạn ghép | Huỷ (miễn phí) |
| `NO_DRIVER` | "Chưa tìm được tài xế" | Đặt lại (lấy báo giá mới) |
| `ACCEPTED`, `PICKING_UP` | Vị trí tài xế chạy trên bản đồ, **mã đón** hiện to | Huỷ; hiện phí huỷ trước khi xác nhận |
| `ARRIVED` | "Tài xế đã đến", mã đón nổi bật | Huỷ |
| `IN_TRIP` | Vị trí xe trên đường tới điểm đến; với Giao hàng hiện **mã giao** để gửi người nhận | — |
| `COMPLETED` | Tổng kết, chuyển sang hoá đơn | Xem hoá đơn |
| `CANCELLED` | Lý do, ai huỷ, phí huỷ nếu có | Đặt chuyến mới |

Trước khi huỷ, gọi `GET /trips/{id}/cancellation-fee?reason=` để hiện đúng số tiền sẽ bị thu.

### 4.3. Tài xế (`/driver`)

Thanh điều hướng dưới: **Trang chủ · Thu nhập · Hồ sơ**.

| Route | Màn hình | Nội dung chính | API / realtime |
|---|---|---|---|
| `/driver/onboarding` | Hoàn thiện hồ sơ | Các bước: thông tin cá nhân → xe → giấy tờ (kèm ảnh) → xác minh email → gửi duyệt. Danh sách "còn thiếu" lấy từ `missingRequirements` | `POST/PUT /drivers/me/profile`, `POST /drivers/me/vehicles`, `POST /drivers/me/documents/files`, `POST /drivers/me/documents`, `POST /drivers/me/profile/submit` |
| `/driver/review` | Trạng thái xét duyệt | `PENDING_REVIEW`, `REJECTED` (kèm lý do, nút sửa và gửi lại), `APPROVED` | `GET /drivers/me/profile` |
| `/driver` | Trang chủ | Bản đồ, công tắc Online / Offline lớn, chọn xe và loại dịch vụ, chỉ báo GPS và kết nối | `POST /drivers/me/availability/online|offline` |
| (lớp phủ) | Offer | Phủ toàn màn hình: điểm đón, khoảng cách tới điểm đón, loại dịch vụ, vòng đếm ngược 15 giây, **Nhận** / **Từ chối** | WebSocket `DRIVER_OFFER_CREATED`; `POST /offers/{id}/accept|decline` |
| `/driver/trips/:id` | Chuyến đang chạy | Một nút hành động chính theo trạng thái (bảng dưới), thông tin người nhận khi Giao hàng | `POST /trips/{id}/start-pickup|arrive|start|complete|cancel` |
| `/driver/earnings` | Ví | Số dư, các dòng thu nhập / điều chỉnh | `GET /wallets/me` |
| `/driver/activity` | Lịch sử chuyến | Như của khách | `GET /trips` |
| `/driver/profile` | Hồ sơ | Xe, giấy tờ và hạn dùng, đăng xuất | `GET /drivers/me/profile` |

Màn hình **Chuyến đang chạy** của tài xế:

| Trạng thái | Nút chính | Ghi chú |
|---|---|---|
| `ACCEPTED` | Bắt đầu đi đón | |
| `PICKING_UP` | Đã đến điểm đón | Server hiện chưa kiểm tra khoảng cách tới điểm đón; giao diện nên hỏi lại nếu tài xế còn ở xa |
| `ARRIVED` | Nhập **mã đón** → Bắt đầu chuyến | Bàn phím số; khách không ra thì huỷ với lý do `CUSTOMER_NO_SHOW` |
| `IN_TRIP` | Hoàn tất | Giao hàng phải nhập **mã giao** của người nhận |

Tài xế không bao giờ thấy mã đón / mã giao từ API; chỉ nhập mã do khách hoặc người nhận đọc.

Tài xế chưa được duyệt bị giữ ở `/driver/onboarding` hoặc `/driver/review`; không vào được trang chủ.

### 4.4. Nhân viên (`/admin`)

Thanh bên chỉ hiện các mục mà vai trò của người đăng nhập dùng được.

| Route | Màn hình | Vai trò | API |
|---|---|---|---|
| `/admin/drivers` | Hàng chờ duyệt, lọc theo trạng thái | Reviewer, Administrator | `GET /admin/drivers?status=` |
| `/admin/drivers/:id` | Hồ sơ đầy đủ, xem ảnh giấy tờ, lịch sử quyết định; Duyệt / Từ chối kèm lý do; Tạm khoá / Mở lại | Reviewer, Administrator | `GET /admin/drivers/{id}`, `…/documents/{docId}/file`, `POST …/decisions`, `…/suspend`, `…/reinstate` |
| `/admin/trips` | Tra cứu chuyến | Support, Administrator | `GET /admin/trips` |
| `/admin/trips/:id` | Chi tiết chuyến, lịch sử trạng thái, bằng chứng giao, số lần nhập sai mã | Support, Administrator | `GET /admin/trips/{id}` |
| `/admin/payments` | Danh sách thanh toán, các lần thử, tạo hoàn tiền | Finance, Administrator | `GET /admin/payments`, `…/{id}/attempts`, `POST …/{id}/refunds` |
| `/admin/wallets/:driverId` | Ví tài xế, tạo điều chỉnh | Finance, Administrator | `GET /admin/wallets/{driverId}`, `POST …/adjustments` |
| `/admin/pricing` | Bảng giá theo phiên bản, thêm phiên bản mới | Administrator | `GET/POST /admin/pricing/rules` |
| `/admin/users/:id` | Đổi vai trò, khoá / mở tài khoản | Administrator | `PUT /admin/users/{id}/roles`, `…/status` |

Quyết định duyệt gửi kèm `profileVersion` đang xem; nếu server trả 409 thì báo "hồ sơ vừa thay đổi" và
tải lại. Hoàn tiền và điều chỉnh ví luôn qua hộp thoại xác nhận có nhập lý do.

## 5. Xác thực và phiên

- **Access token** (sống 15 phút) chỉ giữ trong bộ nhớ. **Refresh token** lưu trong `localStorage` để giữ
  đăng nhập qua lần tải lại trang; đây là đánh đổi có chủ ý, cần CSP chặt và không chèn HTML thô để giảm rủi ro XSS.
- Refresh token **xoay vòng và phát hiện dùng lại**: mọi lần làm mới phải đi qua một promise dùng chung
  (single-flight), nếu không hai request cùng nhận 401 sẽ gửi hai lần refresh và làm phiên bị thu hồi.
- Làm mới chủ động khi token còn dưới 2 phút, và làm mới + gửi lại một lần khi gặp 401.
- Có token mới thì gửi `AUTH { accessToken }` lên WebSocket đang mở (server trả `AUTH_REFRESHED`).
- Đăng xuất: `POST /auth/logout` kèm refresh token, xoá cache TanStack Query, đóng WebSocket.

## 6. Realtime

Một kết nối WebSocket cho mỗi phiên đăng nhập, quản lý trong `shared/realtime`:

| Việc | Cách làm |
|---|---|
| Kết nối | `ws://…/ws?access_token=<token>`; chờ `SESSION_READY` |
| Giữ kết nối | Gửi `PING` theo `heartbeatIntervalSeconds` server trả về (hiện 15 giây; quá 45 giây im lặng thì bị đóng mã 4408) |
| Mất kết nối | Kết nối lại với backoff 1 → 2 → 5 → 10 giây; mã **4401** thì làm mới token trước |
| Đồng bộ lại | Sau mỗi lần kết nối lại: gọi `GET /trips/{id}` lấy snapshot, rồi `SUBSCRIBE_TRIP` |
| Bỏ bản tin cũ | Bỏ event có `aggregateVersion` ≤ `version` của snapshot đang giữ |
| Đẩy vào UI | Event chuyến → ghi vào cache TanStack Query của chuyến đó; offer → store Zustand |

Banner "Mất kết nối, đang thử lại…" hiện ở mọi màn hình khi socket không mở (README §11.5).

**Vị trí tài xế.** Khi online, ứng dụng tài xế dùng `navigator.geolocation.watchPosition` và gửi
`DRIVER_LOCATION_UPDATED` qua WebSocket mỗi 3 giây, `sequence` tăng dần. Server từ chối bản tin cách nhau
dưới 1 giây, và ghép chuyến chỉ dùng vị trí mới hơn 30 giây, nên tab phải mở và có quyền vị trí; mất quyền
hoặc GPS yếu phải báo rõ trên màn hình. Trình duyệt chỉ cấp Geolocation trên HTTPS hoặc `localhost`.

**Vị trí hiển thị cho khách.** Sau `SUBSCRIBE_TRIP`, khách nhận `TRIP_DRIVER_LOCATION`; marker trên bản đồ
được nội suy giữa hai bản tin để xe chạy mượt thay vì nhảy.

## 7. Xử lý lỗi và trạng thái

Mọi phản hồi dùng envelope `{ code, message, data, correlationId, details }`. Lớp API ném một `ApiError`
có `status`, `code`, `details`; giao diện ánh xạ theo `code`, không hiện `message` nội bộ nguyên văn.

| Mã | Giao diện làm gì |
|---|---|
| `VALIDATION_ERROR` | Gắn `details[].field` vào đúng ô nhập |
| `AUTHENTICATION_REQUIRED` | Làm mới token; thất bại thì về trang đăng nhập |
| `ACCESS_DENIED`, `RESOURCE_NOT_FOUND` | Trang "không tìm thấy" (server cố ý không phân biệt hai trường hợp) |
| `QUOTE_EXPIRED` | "Báo giá đã hết hạn" + nút lấy báo giá mới |
| `OFFER_EXPIRED` | Đóng lớp phủ offer, báo "Offer đã hết hạn" |
| `INVALID_TRIP_STATE`, `DRIVER_ALREADY_ASSIGNED` | Tải lại snapshot chuyến rồi hiện trạng thái mới |
| `BUSINESS_RULE_VIOLATION` | Hiện thông báo kèm việc cần làm tiếp (ví dụ sai mã đón) |
| `RATE_LIMIT_EXCEEDED` | "Thao tác quá nhanh, thử lại sau ít giây" |
| `DEPENDENCY_UNAVAILABLE` | "Hệ thống đang bận", nút thử lại |

Quy tắc chung:

- Nút gửi bị khoá trong lúc request đang chạy (chống bấm lặp).
- `POST /trips` luôn kèm `Idempotency-Key` sinh một lần cho mỗi lần bấm đặt; thử lại do lỗi mạng dùng lại đúng khoá đó.
- Client không tự tính giá, không tự đổi trạng thái chuyến; chỉ hiển thị cái server trả.
- Mỗi màn hình có đủ ba trạng thái: đang tải (skeleton), rỗng, lỗi có nút thử lại.

## 8. Cấu trúc thư mục

Đặt trong `frontend/` ở gốc repo.

```
frontend/
├── index.html
├── vite.config.ts
├── src/
│   ├── main.tsx
│   ├── app/                  # router, providers (Query, theme), route guard, bố cục theo vai trò
│   ├── shared/
│   │   ├── api/              # client fetch, ApiError, làm mới token single-flight, kiểu dữ liệu theo contracts/
│   │   ├── auth/             # store phiên, hook useSession, đăng nhập / đăng xuất
│   │   ├── realtime/         # lớp WebSocket, heartbeat, kết nối lại, hook useTripChannel
│   │   ├── map/              # MapView, marker, nội suy vị trí
│   │   ├── ui/               # component shadcn/ui + StatusBadge, Money, Countdown, CodeDisplay
│   │   └── lib/              # định dạng tiền, ngày giờ, toạ độ
│   └── features/
│       ├── auth/             # đăng nhập, đăng ký, quên mật khẩu, xác minh
│       ├── customer/         # booking, quote, trip, receipt, activity, account
│       ├── driver/           # onboarding, home, offer, trip, earnings, profile
│       └── admin/            # drivers, trips, payments, wallets, pricing, users
└── tests/                    # Playwright
```

Mỗi thư mục trong `features/` tự chứa trang, component, hook truy vấn và test của nó; `features` được
import từ `shared`, không import chéo lẫn nhau.

Biến môi trường: `VITE_API_URL` (mặc định `http://localhost:8080`), `VITE_WS_URL` (mặc định
`ws://localhost:8085/ws`). Cổng dev mặc định của Vite (`5173`) đã nằm trong CORS `http://localhost:*` của gateway.

## 9. Hệ thống giao diện

| Thành phần | Quy ước |
|---|---|
| Màu chủ đạo | Một màu thương hiệu cho hành động chính; nền trung tính; hỗ trợ sáng / tối theo hệ điều hành |
| Màu trạng thái | Đang tìm / đang xử lý: vàng hổ phách · đang chạy: xanh dương · hoàn tất: xanh lá · huỷ / lỗi: đỏ · không có tài xế: xám |
| Chữ | Một phông sans hỗ trợ đủ dấu tiếng Việt; mã đón / mã giao dùng chữ số cỡ lớn, giãn cách rộng |
| Vùng chạm | Tối thiểu 44 × 44 px; nút chính rộng hết chiều ngang ở cạnh dưới |
| Tiền | `38.000 ₫`, luôn qua component `Money` |
| Thời gian | Giờ địa phương; đếm ngược dùng component `Countdown` (báo giá, offer, hạn ghép) |
| Truy cập | Tương phản đạt AA, điều hướng được bằng bàn phím ở khu vực quản trị, không chỉ dùng màu để báo trạng thái |

`StatusBadge` là nguồn duy nhất ánh xạ `TripStatus`, `ReviewStatus`, trạng thái thanh toán sang nhãn tiếng
Việt và màu, để ba khu vực hiển thị thống nhất.

## 10. Khoảng trống phía backend

Những thứ giao diện cần mà API hiện chưa có. Mỗi mục cần quyết định: bổ sung backend, hay giao diện tạm đi vòng.

| Thiếu | Ảnh hưởng | Cách đi vòng tạm thời |
|---|---|---|
| Tìm địa chỉ / geocoding | Khách không gõ được địa chỉ để ra toạ độ | Thả ghim trên bản đồ + ô nhập địa chỉ tự do; hoặc gọi Nominatim của OSM từ client (có giới hạn tần suất) |
| Đường đi (polyline) trong báo giá | Chỉ có quãng đường và thời gian, không vẽ được tuyến | Vẽ đường thẳng nối hai điểm |
| Thông tin tài xế cho khách | Chuyến chỉ trả `driverId`; không có tên, biển số, loại xe | Hiện "Tài xế đã nhận chuyến" không kèm chi tiết |
| Cập nhật hồ sơ người dùng | Chỉ có `GET /users/me` | Trang tài khoản chỉ đọc |
| Danh sách người dùng cho quản trị | Chỉ đổi vai trò / trạng thái theo `userId` biết trước | Ô nhập `userId`; chưa có trang danh sách |
| Chọn phương thức thanh toán | Thanh toán chạy tự động qua sandbox sau khi hoàn tất | Hoá đơn chỉ hiển thị kết quả |
| Thông báo đẩy | Offer chỉ tới khi tab đang mở | Âm báo + rung trong tab; ghi rõ giới hạn này |
| Đánh giá chuyến | Chưa có | Bỏ qua ở phiên bản đầu |

## 11. Thứ tự triển khai

| Giai đoạn | Nội dung | Kết quả kiểm chứng được |
|---|---|---|
| 1. Nền tảng | Khởi tạo dự án, lớp API, phiên đăng nhập, lớp realtime, bố cục ba khu vực, CI | Đăng nhập được, WebSocket nhận `SESSION_READY` |
| 2. Khách hàng | Đặt xe → báo giá → chuyến đang chạy → hoá đơn → lịch sử | Đi hết UC-02, UC-03, UC-04 với tài xế bấm từ trang test hiện có |
| 3. Tài xế | Onboarding → online → offer → chạy chuyến → ví | Đi hết UC-01, UC-04, UC-05, UC-06 bằng hai tab |
| 4. Quản trị | Duyệt hồ sơ → chuyến → thanh toán → bảng giá | Thay được các request admin trong collection Postman |
| 5. Hoàn thiện | Trạng thái mất kết nối / GPS yếu, Playwright cho luồng chính, tối ưu bundle | UC-07, UC-08 |

Trang `tools/test-console` giữ lại làm công cụ đóng vai phía bên kia trong lúc từng khu vực chưa xong.
