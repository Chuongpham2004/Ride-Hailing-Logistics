# Test console — bấm tay UC-01 … UC-09

Một trang HTML tĩnh với năm cột: **Khách 1**, **Tài xế A**, **Tài xế B**, **Khách 2**, **Nhân viên**, và ô nhật ký
chung ở dưới. Dùng thay cho việc chạy tay collection Postman. Chỉ dành cho môi trường dev: email và mật khẩu
tài khoản test được lưu trong `localStorage` của trình duyệt.

## Chạy

```bash
python -m http.server 5500 -d tools/test-console
```

Mở `http://localhost:5500`. Phải mở qua `http://localhost:*` vì CORS của api-gateway chỉ cho origin đó; mở
trực tiếp file sẽ không gọi được API. Cần hạ tầng (`docker compose up -d`) và đủ các service đang chạy.

## Chuẩn bị (một lần cho mỗi bộ tài khoản)

1. Điền Gmail vào **Hộp thư nhận mã** nếu user-service chạy `NOTIFICATION_SENDER=smtp`; để trống nếu đọc mã
   trong log (`NOTIFICATION_SENDER=log`).
2. **Tạo bộ tài khoản mới**: đăng ký và đăng nhập 2 khách + 2 tài xế.
3. Mỗi tài xế: **bước 1** → nhập mã 6 số → **bước 2** → **3. Duyệt luôn** (hoặc duyệt ở cột Nhân viên).

Lần sau chỉ cần **Đăng nhập lại tất cả**.

## Đọc nhật ký

| Dòng | Nghĩa |
|---|---|
| `POST … → 200` | Lệnh REST và mã trả về |
| `⇦ TRIP_ACCEPTED {…}` | Bản tin WebSocket nhận được |
| `✔ …` | Kết quả đúng mong đợi (kể cả ca "phải bị chặn" và đã bị chặn) |
| `✘ …` | Kết quả sai mong đợi |
| Dòng đỏ không có ✔/✘ | Lỗi của một lệnh bấm thường |

## Bấm gì cho từng UC

| UC | Cách đi |
|---|---|
| **UC-01** Duyệt tài xế | Tài xế: mục *hồ sơ thiếu / hết hạn* → **Nộp bằng lái đã hết hạn** → **Gửi duyệt** (bị chặn) → **Nộp bằng lái còn hạn** → **Gửi duyệt**. Nhân viên: **Hàng chờ duyệt**, **Từ chối** (có lý do) → tài xế bấm **Gửi duyệt** lại → **Duyệt** → **Xem hồ sơ + lịch sử quyết định**; **Duyệt với phiên bản hồ sơ sai** phải bị chặn. Ca bị chặn: khách *Xem hàng chờ duyệt*, tài xế *Tải file giấy tờ của tài xế kia*. |
| **UC-02** Báo giá | Khách: **Lấy báo giá** (xem giá, surge, rule, đồng hồ hết hạn) → **Đọc lại báo giá**. Ca bị chặn ở Khách 2: *Đọc báo giá của khách kia*, *Đặt chuyến bằng báo giá của khách kia*; ở Khách 1: *Báo giá với toạ độ sai*; ở tài xế: *Xin báo giá như khách*. Hết hạn: đợi đồng hồ về 0 rồi bấm **Đặt chuyến**. |
| **UC-03** Đặt và ghép | Tài xế A **Bật online** → Khách 1 **Báo giá + đặt luôn** → **Gửi lại lệnh đặt (cùng khoá)** và **Cùng khoá, nội dung khác** → tài xế A **Nhận chuyến**. Khi A đang bận, Khách 2 đặt chuyến: A không được nhận offer thứ hai. Tài xế B: *Nhận offer của tài xế kia* phải bị chặn. |
| **UC-04** Chở khách | Tài xế: **Đi đón** → **Đã đến** → gõ mã đón (hiện ở cột khách) → **Bắt đầu** → **Hoàn tất**. Khách thấy trạng thái và vị trí tài xế đổi theo WebSocket. Sau đó: **Lịch sử trạng thái**, **Thanh toán** (khách), **Ví** (tài xế). Thử gõ sai mã, hoặc khách *Tự bấm hoàn tất chuyến*. |
| **UC-05** Giao hàng | Khách chọn `DELIVERY`, đặt chuyến; ca *Đặt giao hàng thiếu người nhận* cần một báo giá DELIVERY chưa dùng. Tài xế chạy như UC-04, **Hoàn tất** cần mã giao. Nhân viên: **← Khách 1** → **Chi tiết chuyến** (bằng chứng giao, số lần sai mã). Khách 2: *Xem chuyến của khách kia*. |
| **UC-06** Cạnh tranh | Cả hai tài xế online. Khách đặt chuyến: đúng một tài xế có offer. **Từ chối**, hoặc đợi hết 15 giây → offer sang tài xế kia. *Nhận lại offer đã từ chối / hết hạn* phải bị chặn. Sau khi khách huỷ, cả hai tài xế phải về `AVAILABLE`. |
| **UC-07** Mất realtime | Khách **Ngắt / nối WebSocket** → tài xế đổi trạng thái chuyến → khách nối lại: chuyến được tải lại từ snapshot, bản tin cũ bị bỏ qua (dòng "bỏ qua … aggregateVersion"). Tài xế tick **gửi qua WebSocket** để gửi vị trí bằng bản tin, và thử các *bản tin vị trí bất thường*. Khách 2: *Theo dõi chuyến của khách kia (WebSocket)* phải nhận `ERROR`. |
| **UC-08** Huỷ có phí | Tài xế ở `ARRIVED`: chọn `CUSTOMER_NO_SHOW` → **Xem trước phí huỷ** → **Huỷ chuyến** → khách bấm **Thanh toán** để thấy phí. Khách huỷ trong 120 giây đầu: phí 0. Nhân viên: **Huỷ hộ** (và ca ghi chú quá ngắn), **Thanh toán của chuyến** → **Hoàn tiền** → **Gửi lại (cùng khoá)** → hai ca bị chặn → **Điều chỉnh ví** → **Gửi lại (cùng khoá)** → **Xem ví**. |
| **UC-09** Vị trí | Tài xế: mục *bản tin vị trí bất thường*, gửi lần lượt từng loại rồi **Vị trí server đang giữ**. **Tắt online** rồi **Gửi vị trí** phải bị chặn. Tài xế offline hoặc không gửi vị trí → khách đặt chuyến ra `NO_DRIVER`. Khách: *Gửi vị trí như tài xế*, *Đọc vị trí tài xế*. |

Ghi chú:

- **Bật online** tự bật "tự gửi mỗi 3 s", vì ghép chuyến chỉ dùng vị trí mới hơn 30 giây. Bỏ tick khi cần kiểm
  tra ca tài xế không có vị trí.
- Sau bản tin *Nhảy ra Hà Nội*, các bản tin thường kế tiếp có thể tiếp tục bị đánh dấu `SUSPICIOUS`.
- Các ca "của khách kia" / "của tài xế kia" lấy dữ liệu từ cột còn lại, nên cột đó phải có chuyến, báo giá
  hoặc offer trước.

## Không làm được ở đây

| Phần | Lý do | Dùng gì thay |
|---|---|---|
| Callback thanh toán (`refund.succeeded`, replay, chữ ký sai) | Trình duyệt không được gửi header `X-Rhl-Signature`: CORS của gateway không cho, và callback vốn là lệnh máy chủ gọi máy chủ | Collection Postman, thư mục UC-08 |
| Upload ảnh giấy tờ | Chưa có trong trang | Collection Postman, thư mục UC-01 |
| 100 accept đồng thời (UC-06) | Không bấm tay được | `TripServiceIT` |
| Tải 100 tài xế × 3 s, đo p95 (UC-04, UC-09) | Cần công cụ tải | Chưa có (`tools/driver-simulator` trong README gốc chưa được viết) |
