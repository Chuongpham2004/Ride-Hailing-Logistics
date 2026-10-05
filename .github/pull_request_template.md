## Mô tả

<!-- Thay đổi gì và vì sao. Tiêu đề PR theo Conventional Commits, ví dụ: feat(trip): atomic offer acceptance -->

## Truy vết yêu cầu (SRS-RHL-002)

<!-- Liệt kê mã yêu cầu liên quan, ví dụ: FR-MAT-010, NFR-REL-003, UC-06 -->

- 

## Loại thay đổi

- [ ] Tính năng mới
- [ ] Sửa lỗi
- [ ] Refactor / hiệu năng
- [ ] Hợp đồng (OpenAPI, event JSON Schema, WebSocket) — **ảnh hưởng service khác**
- [ ] Migration database (Flyway)
- [ ] Tài liệu / CI

## Checklist

- [ ] Có unit test cho logic mới; integration test (Testcontainers) nếu chạm DB/Kafka/Redis
- [ ] Không phá tương thích ngược API/event trong cùng version (NFR-COMP-001/002)
- [ ] Thao tác ghi quan trọng idempotent (COM-008, BR-015)
- [ ] Kiểm tra quyền và quyền sở hữu tài nguyên phía server (FR-IAM-010)
- [ ] Không log PII, token, vị trí chính xác hay dữ liệu tài chính nhạy cảm (NFR-SEC-009)
- [ ] Tham số nghiệp vụ nằm trong cấu hình, không hard-code (NFR-MNT-005)
- [ ] Tiền dùng `long`/`BigDecimal`, thời gian lưu UTC
- [ ] README / contracts đã cập nhật nếu cần

## Kiểm thử

<!-- Đã kiểm thử thế nào, kết quả ra sao -->
