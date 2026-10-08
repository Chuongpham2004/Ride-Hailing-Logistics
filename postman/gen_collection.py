"""Generates RHL-E2E.postman_collection.json (Postman collection v2.1) for UC-01..UC-09.

Edit this file, not the JSON, then run: python postman/gen_collection.py
"""
import json
import pathlib
import sys
import uuid

OUT = sys.argv[1] if len(sys.argv) > 1 else str(pathlib.Path(__file__).with_name("RHL-E2E.postman_collection.json"))

PICKUP = {"latitude": 10.7725, "longitude": 106.6980, "address": "Chợ Bến Thành, Quận 1"}
DROPOFF = {"latitude": 10.7949, "longitude": 106.7219, "address": "Landmark 81, Bình Thạnh"}
NEAR_PICKUP = (10.7730, 106.6985)


def lines(s):
    return [l for l in s.strip("\n").split("\n")]


def status(code):
    return f'pm.test("HTTP {code}", () => pm.response.to.have.status({code}));'


def status4xx():
    return 'pm.test("HTTP 4xx (bị từ chối)", () => pm.expect(pm.response.code).to.be.within(400, 499));'


def status_in(*codes):
    return f'pm.test("HTTP {"/".join(map(str, codes))}", () => pm.expect([{", ".join(map(str, codes))}]).to.include(pm.response.code));'


DATA = "const d = (pm.response.json() || {}).data;"


def req(name, method, path, token=None, body=None, tests="", pre="", headers=None, desc=None, raw_body=None,
        formdata=None):
    url = "{{baseUrl}}" + path
    r = {"method": method, "header": [], "url": url}
    if token:
        r["auth"] = {"type": "bearer", "bearer": [{"key": "token", "value": "{{" + token + "}}", "type": "string"}]}
    else:
        r["auth"] = {"type": "noauth"}
    for k, v in (headers or {}).items():
        r["header"].append({"key": k, "value": v})
    if body is not None or raw_body is not None:
        r["header"].append({"key": "Content-Type", "value": "application/json"})
        r["body"] = {"mode": "raw", "raw": raw_body if raw_body is not None else json.dumps(body, ensure_ascii=False,
                                                                                               indent=2),
                     "options": {"raw": {"language": "json"}}}
    if formdata is not None:
        r["body"] = {"mode": "formdata", "formdata": formdata}
    if desc:
        r["description"] = desc
    item = {"name": name, "request": r, "event": []}
    if pre:
        item["event"].append({"listen": "prerequest", "script": {"type": "text/javascript", "exec": lines(pre)}})
    if tests:
        item["event"].append({"listen": "test", "script": {"type": "text/javascript", "exec": lines(tests)}})
    return item


def folder(name, items, desc=None):
    f = {"name": name, "item": items}
    if desc:
        f["description"] = desc
    return f


def wait(ms):
    return f"setTimeout(() => {{}}, {ms});"


# ---------- reusable steps ----------

def login(name, ident_var, pass_var, token_var, id_var=None):
    tests = status(200) + "\n" + DATA + f'\npm.collectionVariables.set("{token_var}", d.accessToken);'
    items = [req(name, "POST", "/api/v1/auth/login",
                 raw_body='{\n  "identifier": "{{' + ident_var + '}}",\n  "password": "{{' + pass_var + '}}"\n}',
                 tests=tests)]
    if id_var:
        items.append(req(name.replace("Đăng nhập", "Lấy id") if "Đăng nhập" in name else name + " - me", "GET",
                         "/api/v1/users/me", token=token_var,
                         tests=status(200) + "\n" + DATA + f'\npm.collectionVariables.set("{id_var}", d.id);'))
    return items


def register(name, email_var, full_name, role="CUSTOMER", expect=201):
    return req(name, "POST", "/api/v1/auth/register",
               raw_body='{\n  "email": "{{' + email_var + '}}",\n  "password": "{{password}}",\n  "fullName": "'
                        + full_name + '",\n  "role": "' + role + '"\n}',
               tests=status(expect) if expect != "4xx" else status4xx())


def report_location(name, token, lat, lng, accuracy=8, seq_expr="Date.now()", seq_var="lastSeq",
                    expect_outcome="CURRENT", ts_expr="new Date().toISOString()", expect_status=200):
    pre = f"""
pm.collectionVariables.set("seq", String({seq_expr}));
pm.collectionVariables.set("deviceTs", {ts_expr});
"""
    raw = ('{\n  "sequence": {{seq}},\n  "latitude": ' + str(lat) + ',\n  "longitude": ' + str(lng)
           + ',\n  "accuracyMeters": ' + str(accuracy)
           + ',\n  "headingDegrees": 90,\n  "speedMetersPerSecond": 5,\n  "deviceTimestamp": "{{deviceTs}}"\n}')
    if expect_status == 200:
        tests = status(200) + "\n" + DATA + f"""
pm.test("outcome = {expect_outcome}", () => pm.expect(d.outcome).to.eql("{expect_outcome}"));
if (d.outcome === "CURRENT") pm.collectionVariables.set("{seq_var}", String(d.sequence));"""
    elif expect_status == "4xx":
        tests = status4xx()
    else:
        tests = status(expect_status)
    return req(name, "POST", "/api/v1/locations/me", token=token, raw_body=raw, pre=pre, tests=tests)


def quote(name, service_type, token="customerToken", quote_var="quoteId"):
    body = {"serviceType": service_type, "pickup": PICKUP, "dropoff": DROPOFF}
    tests = status(201) + "\n" + DATA + f"""
pm.test("Giá > 0, làm tròn 1000, VND", () => {{
  pm.expect(d.total).to.be.above(0);
  pm.expect(d.total % 1000).to.eql(0);
  pm.expect(d.currency).to.eql("VND");
}});
pm.test("Có rule version và hạn dùng", () => {{
  pm.expect(d.ruleVersion).to.be.a("number");
  pm.expect(new Date(d.expiresAt).getTime()).to.be.above(Date.now());
}});
pm.collectionVariables.set("{quote_var}", d.id);
pm.collectionVariables.set("{quote_var}Total", String(d.total));
pm.collectionVariables.set("{quote_var}Surge", d.surgeConfirmationRequired ? String(d.surgeMultiplier) : "");"""
    return req(name, "POST", "/api/v1/quotes", token=token, body=body, tests=tests)


def book(name, trip_var="tripId", quote_var="quoteId", token="customerToken", delivery=None, key_var="idemKey",
         new_key=True, expect=201, extra_tests=""):
    pre = ""
    if new_key:
        pre += f'pm.collectionVariables.set("{key_var}", "trip-" + Date.now() + "-" + Math.floor(Math.random() * 1e6));\n'
    pre += f"""const body = {{ quoteId: pm.collectionVariables.get("{quote_var}") }};
const surge = pm.collectionVariables.get("{quote_var}Surge");
if (surge) body.acceptedSurgeMultiplier = Number(surge);
"""
    if delivery:
        pre += "body.delivery = " + json.dumps(delivery, ensure_ascii=False) + ";\n"
    pre += 'pm.collectionVariables.set("tripBody", JSON.stringify(body, null, 2));'
    if expect == 201:
        tests = status(201) + "\n" + DATA + f"""
pm.test("Trip đang tìm tài xế", () => pm.expect(["CREATED", "MATCHING"]).to.include(d.status));
pm.collectionVariables.set("{trip_var}", d.id);""" + extra_tests
    else:
        tests = status4xx()
    return req(name, "POST", "/api/v1/trips", token=token, headers={"Idempotency-Key": "{{" + key_var + "}}"},
               raw_body="{{tripBody}}", pre=pre, tests=tests)


def find_offer(name, driver_token, trip_var="tripId", offer_var="offerId", wait_ms=2500):
    tests = status(200) + "\n" + DATA + f"""
const offer = (d || []).find(o => o.tripId === pm.collectionVariables.get("{trip_var}"));
pm.test("Có offer cho trip (nếu trống: tài xế chưa online / vị trí quá 30 s)", () => pm.expect(offer).to.exist);
if (offer) {{
  pm.expect(offer.status).to.eql("PENDING");
  pm.collectionVariables.set("{offer_var}", offer.id);
}}"""
    return req(name, "GET", "/api/v1/offers", token=driver_token, pre=wait(wait_ms), tests=tests)


def accept(name, driver_token, offer_var="offerId", trip_var="tripId", driver_id_var="driverAId"):
    tests = status(200) + "\n" + DATA + f"""
pm.test("Trip ACCEPTED, đúng tài xế", () => {{
  pm.expect(d.status).to.eql("ACCEPTED");
  pm.expect(d.id).to.eql(pm.collectionVariables.get("{trip_var}"));
  pm.expect(d.driverId).to.eql(pm.collectionVariables.get("{driver_id_var}"));
}});"""
    return req(name, "POST", "/api/v1/offers/{{" + offer_var + "}}/accept", token=driver_token, tests=tests)


def advance(name, action, driver_token, expected, trip_var="tripId", code_var=None, raw_code=None, expect_ok=True):
    raw = None
    if code_var:
        raw = '{\n  "code": "{{' + code_var + '}}"\n}'
    elif raw_code is not None:
        raw = '{\n  "code": "' + raw_code + '"\n}'
    if expect_ok:
        tests = status(200) + "\n" + DATA + f'\npm.test("status = {expected}", () => pm.expect(d.status).to.eql("{expected}"));'
    else:
        tests = status4xx()
    return req(name, "POST", "/api/v1/trips/{{" + trip_var + "}}/" + action, token=driver_token, raw_body=raw,
               tests=tests)


def cancel(name, token, reason, trip_var="tripId", note=None, expect_ok=True, by=None):
    body = {"reason": reason}
    if note:
        body["note"] = note
    if expect_ok:
        tests = status(200) + "\n" + DATA + '\npm.test("CANCELLED", () => pm.expect(d.status).to.eql("CANCELLED"));'
        if by:
            tests += f'\npm.test("cancelledBy = {by}", () => pm.expect(d.cancelledBy).to.eql("{by}"));'
    else:
        tests = status4xx()
    return req(name, "POST", "/api/v1/trips/{{" + trip_var + "}}/cancel", token=token, body=body, tests=tests)


def get_trip(name, token, trip_var="tripId", tests_extra="", expect=200):
    tests = (status(expect) + "\n" + DATA + tests_extra) if expect == 200 else status_in(403, 404)
    return req(name, "GET", "/api/v1/trips/{{" + trip_var + "}}", token=token, tests=tests)


def payments_for_trip(name, token, trip_var, tests_extra, wait_ms=3000):
    return req(name, "GET", "/api/v1/payments?tripId={{" + trip_var + "}}", token=token, pre=wait(wait_ms),
               tests=status(200) + "\n" + DATA + tests_extra)


def online(name, token, vehicle_var, expect_ok=True):
    raw = '{\n  "vehicleId": "{{' + vehicle_var + '}}",\n  "serviceTypes": ["RIDE", "DELIVERY"]\n}'
    tests = (status(200) + "\n" + DATA +
             '\npm.test("AVAILABLE", () => pm.expect(d.availability).to.eql("AVAILABLE"));'
             '\n// location-service learns it through Kafka; wait before reporting a location\n'
             + wait(2000)) if expect_ok else status4xx()
    return req(name, "POST", "/api/v1/drivers/me/availability/online", token=token, raw_body=raw, tests=tests)


def offline(name, token):
    return req(name, "POST", "/api/v1/drivers/me/availability/offline", token=token,
               tests=status(200) + "\n" + DATA + '\npm.test("OFFLINE", () => pm.expect(d.availability).to.eql("OFFLINE"));'
               + '\n// location-service learns it through Kafka\n' + wait(2000))


def full_booking(prefix, trip_var, driver="A", service="RIDE", delivery=None):
    """Driver reports location, customer quotes and books, driver accepts."""
    tok = f"driver{driver}Token"
    return [
        report_location(f"{prefix} Tài xế {driver} gửi vị trí gần điểm đón", tok, *NEAR_PICKUP),
        quote(f"{prefix} Khách lấy báo giá {service}", service),
        book(f"{prefix} Khách đặt chuyến", trip_var=trip_var, delivery=delivery),
        find_offer(f"{prefix} Tài xế {driver} xem offer", tok, trip_var=trip_var),
        accept(f"{prefix} Tài xế {driver} nhận chuyến", tok, trip_var=trip_var, driver_id_var=f"driver{driver}Id"),
    ]


# ---------- driver onboarding (UC-01) ----------

def onboarding(x, expired_license=False):
    tok = f"driver{x}Token"
    items = [
        req(f"[{x}] Tạo hồ sơ tài xế", "POST", "/api/v1/drivers/me/profile", token=tok,
            body={"fullName": f"Tài Xế {x} E2E", "dateOfBirth": "1990-05-20", "serviceTypes": ["RIDE", "DELIVERY"]},
            tests=status(201) + "\n" + DATA + '\npm.test("DRAFT", () => pm.expect(d.reviewStatus).to.eql("DRAFT"));'),
        req(f"[{x}] Thêm xe máy", "POST", "/api/v1/drivers/me/vehicles", token=tok,
            pre=f'pm.collectionVariables.set("plate{x}", "59X" + String(Date.now()).slice(-6));',
            raw_body='{\n  "type": "MOTORBIKE",\n  "plateNumber": "{{plate' + x
                     + '}}",\n  "brand": "Honda",\n  "model": "Wave Alpha",\n  "color": "Đen",\n  "manufactureYear": 2022\n}',
            tests=status(201) + "\n" + DATA + f'\npm.collectionVariables.set("vehicle{x}Id", d.id);'),
        req(f"[{x}] (Tuỳ chọn) Upload ảnh CCCD", "POST", "/api/v1/drivers/me/documents/files", token=tok,
            formdata=[{"key": "file", "type": "file", "src": []}],
            desc="Chọn một file JPEG/PNG/PDF ≤ 5 MB ở tab Body. Bỏ qua được: fileId là tuỳ chọn.",
            tests=status(201) + "\n" + DATA + f'\npm.collectionVariables.set("file{x}Id", d.id || d.fileId);'),
    ]

    def doc(title, dtype, vehicle=False, issued="2024-01-01", expires="2030-01-01", save=None):
        body = '{\n  "type": "' + dtype + '",\n'
        if vehicle:
            body += '  "vehicleId": "{{vehicle' + x + 'Id}}",\n'
        body += ('  "documentNumber": "' + dtype[:3] + '-{{runId}}-' + x + '",\n  "issuedOn": "' + issued
                 + '",\n  "expiresOn": "' + expires + '"\n}')
        t = status(201)
        if save:
            t += "\n" + DATA + f'\npm.collectionVariables.set("{save}", d.id);'
        return req(title, "POST", "/api/v1/drivers/me/documents", token=tok, raw_body=body, tests=t)

    items.append(doc(f"[{x}] Nộp CCCD (NATIONAL_ID)", "NATIONAL_ID", save=f"doc{x}Id"))
    if expired_license:
        items.append(doc(f"[{x}] Nộp bằng lái ĐÃ HẾT HẠN", "DRIVER_LICENSE", issued="2015-01-01", expires="2020-01-01"))
    else:
        items.append(doc(f"[{x}] Nộp bằng lái (DRIVER_LICENSE)", "DRIVER_LICENSE"))
    items.append(doc(f"[{x}] Nộp đăng ký xe", "VEHICLE_REGISTRATION", vehicle=True))
    items.append(doc(f"[{x}] Nộp bảo hiểm xe", "VEHICLE_INSURANCE", vehicle=True))
    items.append(req(f"[{x}] [NEG] Gửi duyệt khi chưa xác minh email → bị chặn", "POST",
                     "/api/v1/drivers/me/profile/submit", token=tok, tests=status4xx()))
    items.append(req(f"[{x}] Gửi mã xác minh email", "POST", "/api/v1/users/me/contacts/email/verification",
                     token=tok, tests=status(202),
                     desc=f"Mở log user-service, tìm dòng `DEV NOTIFICATION EMAIL to ... code XXXXXX`, "
                          f"điền 6 số vào biến collection `otpDriver{x}` rồi chạy request kế tiếp."))
    items.append(req(f"[{x}] Xác nhận mã (điền biến otpDriver{x} trước)", "POST",
                     "/api/v1/users/me/contacts/email/verification/confirm", token=tok,
                     raw_body='{\n  "code": "{{otpDriver' + x + '}}"\n}',
                     tests=status(200) + "\n" + DATA + '\npm.test("emailVerified", () => pm.expect(d.emailVerified).to.eql(true));'))
    if expired_license:
        items.append(req(f"[{x}] Hồ sơ báo thiếu do bằng hết hạn", "GET", "/api/v1/drivers/me/profile", token=tok,
                         tests=status(200) + "\n" + DATA + '\npm.test("missingRequirements không rỗng", () => pm.expect(d.missingRequirements).to.not.be.empty);'))
        items.append(req(f"[{x}] [NEG] Gửi duyệt với bằng hết hạn → bị chặn", "POST",
                         "/api/v1/drivers/me/profile/submit", token=tok, tests=status4xx()))
        items.append(doc(f"[{x}] Nộp lại bằng lái còn hạn", "DRIVER_LICENSE"))
    items.append(req(f"[{x}] Gửi hồ sơ đi duyệt", "POST", "/api/v1/drivers/me/profile/submit", token=tok,
                     tests=status(200) + "\n" + DATA + f"""
pm.test("PENDING_REVIEW, không còn thiếu gì", () => {{
  pm.expect(d.reviewStatus).to.eql("PENDING_REVIEW");
  pm.expect(d.missingRequirements || []).to.be.empty;
}});
pm.collectionVariables.set("profileVersion{x}", String(d.profileVersion));"""))
    return items


def decide(name, x, verdict, reason=None, version_expr=None, expect_ok=True):
    v = version_expr or "{{profileVersion" + x + "}}"
    body = '{\n  "verdict": "' + verdict + '",\n  "profileVersion": ' + v
    if reason:
        body += ',\n  "reason": "' + reason + '"'
    body += "\n}"
    return req(name, "POST", "/api/v1/admin/drivers/{{driver" + x + "Id}}/decisions", token="adminToken",
               raw_body=body, tests=(status(200) if expect_ok else status_in(409, 422)))


# ---------- folders ----------

setup = folder("00. Chuẩn bị tài khoản", [
    req("Tạo runId + đăng nhập admin", "POST", "/api/v1/auth/login",
        pre="""
const runId = Date.now().toString(36);
pm.collectionVariables.set("runId", runId);
["customer", "customer2", "driverA", "driverB"].forEach(p =>
  pm.collectionVariables.set(p + "Email", p.toLowerCase() + "-" + runId + "@e2e.local"));
""",
        raw_body='{\n  "identifier": "{{adminIdentifier}}",\n  "password": "{{adminPassword}}"\n}',
        tests=status(200) + "\n" + DATA + '\npm.collectionVariables.set("adminToken", d.accessToken);',
        desc="Admin được tạo qua BOOTSTRAP_ADMIN_EMAIL / BOOTSTRAP_ADMIN_PASSWORD khi khởi động user-service."),
    register("Đăng ký khách hàng 1", "customerEmail", "Khách Hàng Một"),
    register("[NEG] Đăng ký trùng email → 409", "customerEmail", "Khách Trùng", expect=409),
    req("[NEG] Tự đăng ký vai trò ADMINISTRATOR → bị chặn", "POST", "/api/v1/auth/register",
        raw_body='{\n  "email": "hacker-{{runId}}@e2e.local",\n  "password": "{{password}}",\n  "fullName": "Hacker",\n  "role": "ADMINISTRATOR"\n}',
        tests=status4xx()),
    register("Đăng ký khách hàng 2 (người ngoài cuộc)", "customer2Email", "Khách Hàng Hai"),
    register("Đăng ký tài xế A", "driverAEmail", "Tài Xế A E2E", role="DRIVER"),
    register("Đăng ký tài xế B", "driverBEmail", "Tài Xế B E2E", role="DRIVER"),
    *login("Đăng nhập khách 1", "customerEmail", "password", "customerToken", "customerId"),
    *login("Đăng nhập khách 2", "customer2Email", "password", "customer2Token", "customer2Id"),
    *login("Đăng nhập tài xế A", "driverAEmail", "password", "driverAToken", "driverAId"),
    *login("Đăng nhập tài xế B", "driverBEmail", "password", "driverBToken", "driverBId"),
], desc="Tạo tài khoản mới cho mỗi lượt chạy (email có hậu tố runId), nên chạy lại bao nhiêu lần cũng được.")

uc01 = folder("UC-01 Đăng ký & xét duyệt tài xế", [
    *onboarding("A"),
    *onboarding("B", expired_license=True),
    req("[NEG] Khách hàng xem hàng chờ duyệt → 403", "GET", "/api/v1/admin/drivers", token="customerToken",
        tests=status(403)),
    req("Reviewer xem hàng chờ PENDING_REVIEW", "GET", "/api/v1/admin/drivers?status=PENDING_REVIEW&size=100",
        token="adminToken", tests=status(200) + "\n" + DATA + """
const ids = d.items.map(i => i.driverId);
pm.test("Có cả tài xế A và B", () => {
  pm.expect(ids).to.include(pm.collectionVariables.get("driverAId"));
  pm.expect(ids).to.include(pm.collectionVariables.get("driverBId"));
});"""),
    req("Reviewer xem chi tiết hồ sơ A", "GET", "/api/v1/admin/drivers/{{driverAId}}", token="adminToken",
        tests=status(200) + "\n" + DATA + '\npm.test("Đủ 4 giấy tờ", () => pm.expect(d.profile.documents.length).to.be.at.least(4));'),
    decide("[NEG] Duyệt A với profileVersion sai → conflict", "A", "APPROVE", version_expr="999", expect_ok=False),
    decide("Duyệt A (APPROVE)", "A", "APPROVE"),
    decide("Từ chối B (REJECT)", "B", "REJECT", reason="Ảnh CCCD bị mờ, vui lòng chụp lại"),
    req("[B] Thấy trạng thái REJECTED", "GET", "/api/v1/drivers/me/profile", token="driverBToken",
        tests=status(200) + "\n" + DATA + '\npm.test("REJECTED", () => pm.expect(d.reviewStatus).to.eql("REJECTED"));'),
    req("[B] Gửi duyệt lại", "POST", "/api/v1/drivers/me/profile/submit", token="driverBToken",
        tests=status(200) + "\n" + DATA + """
pm.test("PENDING_REVIEW, version tăng", () => {
  pm.expect(d.reviewStatus).to.eql("PENDING_REVIEW");
  pm.expect(d.profileVersion).to.be.above(Number(pm.collectionVariables.get("profileVersionB")));
});
pm.collectionVariables.set("profileVersionB", String(d.profileVersion));"""),
    decide("Duyệt B (APPROVE)", "B", "APPROVE"),
    req("Lịch sử quyết định của B có ≥ 2 dòng", "GET", "/api/v1/admin/drivers/{{driverBId}}", token="adminToken",
        tests=status(200) + "\n" + DATA + """
pm.test("REJECT rồi APPROVE", () => {
  const ds = d.decisions.map(x => x.decision);
  pm.expect(ds).to.include("REJECTED");
  pm.expect(ds).to.include("APPROVED");
});"""),
    req("[NEG] Tài xế A tải file giấy tờ của B → 404", "GET", "/api/v1/drivers/me/documents/{{docBId}}/file",
        token="driverAToken", tests=status_in(403, 404)),
    req("[NEG] Khách hàng tải file giấy tờ qua API reviewer → 403", "GET",
        "/api/v1/admin/drivers/{{driverBId}}/documents/{{docBId}}/file", token="customerToken", tests=status(403)),
], desc="Có 2 bước THỦ CÔNG: đọc mã OTP trong log user-service và điền vào biến otpDriverA / otpDriverB.")

uc02 = folder("UC-02 Tạo báo giá", [
    quote("Khách lấy báo giá RIDE", "RIDE"),
    req("Đọc lại báo giá → cùng tổng tiền (tái lập được)", "GET", "/api/v1/quotes/{{quoteId}}", token="customerToken",
        tests=status(200) + "\n" + DATA + """
pm.test("Cùng total và ruleVersion", () => pm.expect(String(d.total)).to.eql(pm.collectionVariables.get("quoteIdTotal")));
pm.test("Breakdown có mặt", () => pm.expect(d.breakdown).to.be.an("object"));
pm.test("Surge hiển thị", () => pm.expect(d.surgeMultiplier).to.exist);"""),
    req("[NEG] Khách 2 đọc báo giá của khách 1 → 404", "GET", "/api/v1/quotes/{{quoteId}}", token="customer2Token",
        tests=status_in(403, 404)),
    book("[NEG] Khách 2 đặt chuyến bằng báo giá của khách 1 → bị chặn", token="customer2Token", trip_var="ignored",
         expect="4xx"),
    req("[NEG] Tài xế xin báo giá → 403", "POST", "/api/v1/quotes", token="driverAToken",
        body={"serviceType": "RIDE", "pickup": PICKUP, "dropoff": DROPOFF}, tests=status(403)),
    req("[NEG] Toạ độ sai (lat 91) → 400", "POST", "/api/v1/quotes", token="customerToken",
        body={"serviceType": "RIDE", "pickup": {**PICKUP, "latitude": 91}, "dropoff": DROPOFF}, tests=status(400)),
    quote("Khách lấy báo giá DELIVERY", "DELIVERY", quote_var="deliveryQuoteId"),
    req("Admin xem bảng giá (rule version)", "GET", "/api/v1/admin/pricing/rules", token="adminToken",
        tests=status(200) + "\n" + DATA + '\npm.test("Có rule", () => pm.expect(d).to.not.be.empty);'),
    book("[THỦ CÔNG] Đợi > 5 phút sau báo giá RIDE rồi đặt → hết hạn, bị chặn", trip_var="ignored", expect="4xx"),
], desc="Request cuối chỉ chạy tay sau khi chờ quote hết hạn (TTL 5 phút); khi chạy Runner hãy bỏ chọn nó.")

uc03 = folder("UC-03 Đặt chuyến & ghép tài xế", [
    online("Tài xế A bật online", "driverAToken", "vehicleAId"),
    report_location("Tài xế A gửi vị trí gần điểm đón", "driverAToken", *NEAR_PICKUP),
    quote("Khách lấy báo giá", "RIDE"),
    book("Khách đặt chuyến (Idempotency-Key mới)"),
    req("Gửi lại đúng request (cùng key, cùng body) → cùng trip", "POST", "/api/v1/trips", token="customerToken",
        headers={"Idempotency-Key": "{{idemKey}}"}, raw_body="{{tripBody}}",
        tests=status_in(200, 201) + "\n" + DATA + """
pm.test("Không tạo trip thứ hai", () => pm.expect(d.id).to.eql(pm.collectionVariables.get("tripId")));"""),
    req("[NEG] Cùng key, body khác → 409 IDEMPOTENCY_KEY_REUSED", "POST", "/api/v1/trips", token="customerToken",
        headers={"Idempotency-Key": "{{idemKey}}"},
        raw_body='{\n  "quoteId": "' + str(uuid.UUID(int=1)) + '"\n}',
        tests=status(409) + '\npm.test("code", () => pm.expect(pm.response.json().code).to.eql("IDEMPOTENCY_KEY_REUSED"));'),
    find_offer("Tài xế A thấy offer", "driverAToken"),
    req("[NEG] Tài xế B nhận offer của A → bị chặn", "POST", "/api/v1/offers/{{offerId}}/accept",
        token="driverBToken", tests=status4xx()),
    accept("Tài xế A nhận chuyến", "driverAToken"),
    req("Nhận lại lần nữa → cùng kết quả, không trùng", "POST", "/api/v1/offers/{{offerId}}/accept",
        token="driverAToken", tests=status(200) + "\n" + DATA + """
pm.test("Cùng trip, vẫn ACCEPTED", () => {
  pm.expect(d.id).to.eql(pm.collectionVariables.get("tripId"));
  pm.expect(d.status).to.eql("ACCEPTED");
});"""),
    get_trip("Khách xem trip → có mã đón", "customerToken", tests_extra="""
pm.test("ACCEPTED + pickupCode", () => {
  pm.expect(d.status).to.eql("ACCEPTED");
  pm.expect(d.pickupCode).to.be.a("string");
});
pm.collectionVariables.set("pickupCode", d.pickupCode);"""),
    get_trip("Tài xế xem trip → KHÔNG thấy mã", "driverAToken",
             tests_extra='\npm.test("Không lộ mã cho tài xế", () => pm.expect(d.pickupCode).to.not.be.ok);'),
    req("Tài xế A đang BUSY", "GET", "/api/v1/drivers/me/profile", token="driverAToken",
        pre=wait(1500), tests=status(200) + "\n" + DATA + '\npm.test("BUSY", () => pm.expect(d.availability).to.eql("BUSY"));'),
    quote("Khách 2 lấy báo giá", "RIDE", token="customer2Token", quote_var="quote2Id"),
    book("Khách 2 đặt chuyến khi A đang bận", trip_var="trip2Id", quote_var="quote2Id", token="customer2Token",
         key_var="idemKey2"),
    req("A KHÔNG nhận offer cho chuyến thứ hai (không gán 2 trip)", "GET", "/api/v1/offers", token="driverAToken",
        pre=wait(3000), tests=status(200) + "\n" + DATA + """
pm.test("Không có offer cho trip2", () =>
  pm.expect((d || []).some(o => o.tripId === pm.collectionVariables.get("trip2Id"))).to.eql(false));"""),
    cancel("Dọn dẹp: khách 2 huỷ chuyến thứ hai", "customer2Token", "CHANGED_MIND", trip_var="trip2Id"),
], desc="Chạy liền mạch: vị trí tài xế chỉ được dùng để ghép trong 30 s, offer hết hạn sau 15 s.")

uc04 = folder("UC-04 Theo dõi & hoàn tất chuyến chở khách", [
    advance("[NEG] Đến điểm đón khi chưa bắt đầu đi đón → 409", "arrive", "driverAToken", None, expect_ok=False),
    advance("Tài xế bắt đầu đi đón", "start-pickup", "driverAToken", "PICKING_UP"),
    report_location("Tài xế gửi vị trí đang di chuyển (khách thấy trên WebSocket)", "driverAToken", 10.7728, 106.6983),
    advance("Tài xế đến điểm đón", "arrive", "driverAToken", "ARRIVED"),
    req("[NEG] Bắt đầu chuyến với mã sai → bị chặn", "POST", "/api/v1/trips/{{tripId}}/start", token="driverAToken",
        pre="""
const c = pm.collectionVariables.get("pickupCode") || "0000";
const wrong = c.split("").map(ch => String((Number(ch) + 1) % 10)).join("");
pm.collectionVariables.set("wrongCode", wrong);""",
        raw_body='{\n  "code": "{{wrongCode}}"\n}', tests=status4xx()),
    advance("Bắt đầu chuyến với mã đúng", "start", "driverAToken", "IN_TRIP", code_var="pickupCode"),
    req("[NEG] Khách tự bấm hoàn tất → 403", "POST", "/api/v1/trips/{{tripId}}/complete", token="customerToken",
        tests=status(403)),
    advance("Tài xế hoàn tất chuyến", "complete", "driverAToken", "COMPLETED"),
    advance("Hoàn tất lặp → trả lại trip COMPLETED (idempotent, không event mới)", "complete", "driverAToken",
            "COMPLETED"),
    req("Lịch sử trạng thái đúng thứ tự", "GET", "/api/v1/trips/{{tripId}}/history", token="customerToken",
        tests=status(200) + "\n" + DATA + """
const seq = d.map(x => x.toStatus);
const expected = ["ACCEPTED", "PICKING_UP", "ARRIVED", "IN_TRIP", "COMPLETED"];
pm.test("Thứ tự: " + expected.join(" → "), () => {
  const idx = expected.map(s => seq.indexOf(s));
  idx.forEach(i => pm.expect(i).to.be.at.least(0));
  pm.expect(idx).to.eql([...idx].sort((a, b) => a - b));
});"""),
    payments_for_trip("Thanh toán cước: đúng 1 khoản, SUCCEEDED, = giá báo", "customerToken", "tripId", """
pm.test("Đúng 1 payment TRIP_FARE", () => pm.expect(d.filter(p => p.purpose === "TRIP_FARE").length).to.eql(1));
const p = d.find(p => p.purpose === "TRIP_FARE");
pm.test("SUCCEEDED, amount = giá báo", () => {
  pm.expect(p.status).to.eql("SUCCEEDED");
  pm.expect(String(p.amount)).to.eql(pm.collectionVariables.get("quoteIdTotal"));
});
pm.collectionVariables.set("farePaymentId", p.id);"""),
    req("Ví tài xế A có thu nhập từ chuyến (1 dòng EARNING)", "GET", "/api/v1/wallets/me?limit=100",
        token="driverAToken", tests=status(200) + "\n" + DATA + """
const rows = d.entries.filter(e => e.tripId === pm.collectionVariables.get("tripId"));
pm.test("Có dòng ví cho trip", () => pm.expect(rows).to.not.be.empty);
pm.test("Không ghi trùng loại", () => {
  const types = rows.map(r => r.type);
  pm.expect(new Set(types).size).to.eql(types.length);
});
pm.collectionVariables.set("walletBalanceA", String(d.balance));"""),
    payments_for_trip("Gọi lại: vẫn chỉ 1 payment (không trùng)", "customerToken", "tripId",
                      '\npm.test("Vẫn 1", () => pm.expect(d.length).to.eql(1));', wait_ms=2000),
    req("[NEG] Khách 2 xem payment của khách 1 → 404", "GET", "/api/v1/payments/{{farePaymentId}}",
        token="customer2Token", tests=status_in(403, 404)),
], desc="Tiếp tục trip của UC-03. Phần WebSocket (khách xem vị trí realtime) làm theo hướng dẫn trong README.")

DELIVERY = {"recipientName": "Nguyễn Văn Nhận", "recipientPhone": "0901234567",
            "packageDescription": "Hộp tài liệu", "packageSize": "SMALL", "packageWeightGrams": 1500,
            "instructions": "Gọi trước khi đến"}

uc05 = folder("UC-05 Hoàn tất giao hàng", [
    report_location("Tài xế A gửi vị trí", "driverAToken", *NEAR_PICKUP),
    quote("Khách lấy báo giá DELIVERY", "DELIVERY"),
    book("[NEG] Đặt DELIVERY thiếu thông tin người nhận → bị chặn", trip_var="ignored", expect="4xx"),
    book("Khách đặt giao hàng", trip_var="deliveryTripId", delivery=DELIVERY),
    find_offer("Tài xế A thấy offer giao hàng", "driverAToken", trip_var="deliveryTripId"),
    accept("Tài xế A nhận", "driverAToken", trip_var="deliveryTripId"),
    get_trip("Khách xem: có người nhận + mã đón + mã giao", "customerToken", trip_var="deliveryTripId", tests_extra="""
pm.test("Có delivery + 2 mã", () => {
  pm.expect(d.delivery.recipientName).to.eql("Nguyễn Văn Nhận");
  pm.expect(d.pickupCode).to.be.a("string");
  pm.expect(d.deliveryCode).to.be.a("string");
});
pm.collectionVariables.set("pickupCode", d.pickupCode);
pm.collectionVariables.set("deliveryCode", d.deliveryCode);"""),
    get_trip("Tài xế xem: thấy người nhận, KHÔNG thấy mã", "driverAToken", trip_var="deliveryTripId", tests_extra="""
pm.test("Thấy người nhận", () => pm.expect(d.delivery).to.exist);
pm.test("Không có mã", () => { pm.expect(d.pickupCode).to.not.be.ok; pm.expect(d.deliveryCode).to.not.be.ok; });"""),
    get_trip("[NEG] Khách 2 xem trip giao hàng → 404", "customer2Token", trip_var="deliveryTripId", expect=404),
    advance("Đi đón hàng", "start-pickup", "driverAToken", "PICKING_UP", trip_var="deliveryTripId"),
    advance("Đến điểm lấy hàng", "arrive", "driverAToken", "ARRIVED", trip_var="deliveryTripId"),
    advance("Lấy hàng (mã đón)", "start", "driverAToken", "IN_TRIP", trip_var="deliveryTripId", code_var="pickupCode"),
    advance("[NEG] Giao không có mã → bị chặn", "complete", "driverAToken", None, trip_var="deliveryTripId",
            expect_ok=False),
    req("[NEG] Giao với mã sai → bị chặn (bị đếm)", "POST", "/api/v1/trips/{{deliveryTripId}}/complete",
        token="driverAToken", pre="""
const c = pm.collectionVariables.get("deliveryCode") || "0000";
pm.collectionVariables.set("wrongCode", c.split("").map(ch => String((Number(ch) + 1) % 10)).join(""));""",
        raw_body='{\n  "code": "{{wrongCode}}"\n}', tests=status4xx()),
    advance("Giao thành công với mã giao đúng", "complete", "driverAToken", "COMPLETED", trip_var="deliveryTripId",
            code_var="deliveryCode"),
    req("Staff xem chi tiết: có bằng chứng giao + 1 lần sai mã", "GET", "/api/v1/admin/trips/{{deliveryTripId}}",
        token="adminToken", tests=status(200) + "\n" + DATA + """
pm.test("deliveryProof gắn đúng trip", () => pm.expect(d.deliveryProof).to.exist);
pm.test("Đếm sai mã giao ≥ 1", () => pm.expect(d.deliveryCodeFailures).to.be.at.least(1));"""),
    payments_for_trip("Cước giao hàng ghi đúng 1 lần", "customerToken", "deliveryTripId", """
pm.test("Đúng 1 payment SUCCEEDED", () => {
  pm.expect(d.length).to.eql(1);
  pm.expect(d[0].status).to.eql("SUCCEEDED");
});"""),
])

uc06 = folder("UC-06 Cạnh tranh chấp nhận chuyến", [
    online("Tài xế B bật online", "driverBToken", "vehicleBId"),
    report_location("A gửi vị trí", "driverAToken", *NEAR_PICKUP),
    report_location("B gửi vị trí (cùng chỗ)", "driverBToken", *NEAR_PICKUP),
    quote("Khách lấy báo giá", "RIDE"),
    book("Khách đặt chuyến", trip_var="raceTripId"),
    req("Xác định ai nhận offer trước (A)", "GET", "/api/v1/offers", token="driverAToken", pre=wait(2500),
        tests=status(200) + "\n" + DATA + """
const o = (d || []).find(x => x.tripId === pm.collectionVariables.get("raceTripId"));
pm.collectionVariables.set("offerA", o ? o.id : "");"""),
    req("Xác định ai nhận offer trước (B)", "GET", "/api/v1/offers", token="driverBToken",
        tests=status(200) + "\n" + DATA + """
const o = (d || []).find(x => x.tripId === pm.collectionVariables.get("raceTripId"));
const a = pm.collectionVariables.get("offerA");
pm.test("Đúng MỘT tài xế có offer", () => pm.expect(Boolean(a) !== Boolean(o)).to.eql(true));
const first = a ? "A" : "B", second = a ? "B" : "A";
const v = n => pm.collectionVariables.get(n);
pm.collectionVariables.set("firstOfferId", a || (o && o.id));
pm.collectionVariables.set("firstToken", v("driver" + first + "Token"));
pm.collectionVariables.set("secondToken", v("driver" + second + "Token"));
pm.collectionVariables.set("secondDriverId", v("driver" + second + "Id"));
console.log("Offer đầu tiên thuộc tài xế " + first);"""),
    req("[NEG] Tài xế kia nhận offer không phải của mình → bị chặn", "POST",
        "/api/v1/offers/{{firstOfferId}}/accept", token="secondToken", tests=status4xx()),
    req("Tài xế đầu từ chối", "POST", "/api/v1/offers/{{firstOfferId}}/decline", token="firstToken",
        tests=status(200) + "\n" + DATA + '\npm.test("DECLINED", () => pm.expect(d.status).to.eql("DECLINED"));'),
    req("[NEG] Từ chối xong lại nhận → 409", "POST", "/api/v1/offers/{{firstOfferId}}/accept", token="firstToken",
        tests=status(409)),
    find_offer("Tài xế thứ hai nhận được offer mới", "secondToken", trip_var="raceTripId", offer_var="secondOfferId"),
    accept("Tài xế thứ hai nhận", "secondToken", offer_var="secondOfferId", trip_var="raceTripId",
           driver_id_var="secondDriverId"),
    req("Nhận lặp → cùng trip, không event trùng", "POST", "/api/v1/offers/{{secondOfferId}}/accept",
        token="secondToken", tests=status(200) + "\n" + DATA + """
pm.test("Cùng trip", () => pm.expect(d.id).to.eql(pm.collectionVariables.get("raceTripId")));"""),
    cancel("Dọn dẹp: khách huỷ (trong 120 s miễn phí)", "customerToken", "CHANGED_MIND", trip_var="raceTripId"),
    req("A không kẹt OFFERED/BUSY", "GET", "/api/v1/drivers/me/profile", token="driverAToken", pre=wait(2000),
        tests=status(200) + "\n" + DATA + '\npm.test("AVAILABLE", () => pm.expect(d.availability).to.eql("AVAILABLE"));'),
    req("B không kẹt OFFERED/BUSY", "GET", "/api/v1/drivers/me/profile", token="driverBToken",
        tests=status(200) + "\n" + DATA + '\npm.test("AVAILABLE", () => pm.expect(d.availability).to.eql("AVAILABLE"));'),

    report_location("[Hết hạn] A gửi vị trí", "driverAToken", *NEAR_PICKUP),
    report_location("[Hết hạn] B gửi vị trí", "driverBToken", *NEAR_PICKUP),
    quote("[Hết hạn] Khách lấy báo giá", "RIDE"),
    book("[Hết hạn] Khách đặt chuyến", trip_var="expiryTripId"),
    req("[Hết hạn] Tìm offer (A)", "GET", "/api/v1/offers", token="driverAToken", pre=wait(2500),
        tests=status(200) + "\n" + DATA + """
const o = (d || []).find(x => x.tripId === pm.collectionVariables.get("expiryTripId"));
pm.collectionVariables.set("offerA", o ? o.id : "");"""),
    req("[Hết hạn] Tìm offer (B)", "GET", "/api/v1/offers", token="driverBToken",
        tests=status(200) + "\n" + DATA + """
const o = (d || []).find(x => x.tripId === pm.collectionVariables.get("expiryTripId"));
const a = pm.collectionVariables.get("offerA");
pm.test("Đúng MỘT tài xế có offer", () => pm.expect(Boolean(a) !== Boolean(o)).to.eql(true));
const first = a ? "A" : "B", second = a ? "B" : "A";
const v = n => pm.collectionVariables.get(n);
pm.collectionVariables.set("firstOfferId", a || (o && o.id));
pm.collectionVariables.set("firstToken", v("driver" + first + "Token"));
pm.collectionVariables.set("secondToken", v("driver" + second + "Token"));
pm.collectionVariables.set("secondDriverId", v("driver" + second + "Id"));"""),
    req("[Hết hạn] Đợi 17 s rồi nhận → 409 OFFER_EXPIRED", "POST", "/api/v1/offers/{{firstOfferId}}/accept",
        token="firstToken", pre=wait(17000),
        tests=status(409) + '\npm.test("OFFER_EXPIRED", () => pm.expect(pm.response.json().code).to.eql("OFFER_EXPIRED"));'),
    find_offer("[Hết hạn] Offer chuyển sang tài xế còn lại", "secondToken", trip_var="expiryTripId",
               offer_var="secondOfferId", wait_ms=1000),
    accept("[Hết hạn] Tài xế còn lại nhận", "secondToken", offer_var="secondOfferId", trip_var="expiryTripId",
           driver_id_var="secondDriverId"),
    cancel("[Hết hạn] Dọn dẹp: khách huỷ", "customerToken", "CHANGED_MIND", trip_var="expiryTripId"),
    offline("Dọn dẹp: B tắt online", "driverBToken"),
], desc="Postman chạy tuần tự nên không bắn được 100 accept đồng thời; phần đó đã có TripServiceIT (10 luồng). "
        "Ở đây kiểm tra: offer chỉ thuộc 1 tài xế, bên thua nhận conflict/expired, nhận lặp không trùng, không ai bị kẹt.")

uc07 = folder("UC-07 Mất & khôi phục realtime", [
    report_location("Tài xế A gửi vị trí seq = N", "driverAToken", *NEAR_PICKUP, seq_var="seqN"),
    report_location("Gửi lại đúng seq N → DUPLICATE", "driverAToken", *NEAR_PICKUP,
                    seq_expr='pm.collectionVariables.get("seqN")', expect_outcome="DUPLICATE"),
    report_location("Bản tin cũ seq N-5000 → bị bỏ qua (DUPLICATE)", "driverAToken", 10.80, 106.70,
                    seq_expr='Number(pm.collectionVariables.get("seqN")) - 5000', expect_outcome="DUPLICATE"),
    report_location("seq mới nhưng giờ thiết bị cũ hơn → OUT_OF_ORDER (không ghi đè)", "driverAToken", 10.80, 106.70,
                    seq_expr='Number(pm.collectionVariables.get("seqN")) + 1',
                    ts_expr="new Date(Date.now() - 5000).toISOString()", expect_outcome="OUT_OF_ORDER"),
    req("Vị trí hiện tại vẫn là seq N (không bị bản tin cũ ghi đè)", "GET", "/api/v1/locations/me",
        token="driverAToken", tests=status(200) + "\n" + DATA + """
pm.test("sequence = N", () => pm.expect(String(d.sequence)).to.eql(pm.collectionVariables.get("seqN")));"""),
    *full_booking("[Trip WS]", "wsTripId"),
    get_trip("[Trip WS] Snapshot sau khi kết nối lại (GET trip)", "customerToken", trip_var="wsTripId",
             tests_extra='\npm.test("Có version để so với aggregateVersion", () => pm.expect(d.version).to.be.a("number"));'),
], desc="Phần chính là WebSocket, làm tay theo README mục UC-07 với trip `wsTripId`. "
        "Trip này để nguyên ở ACCEPTED, UC-08 dùng tiếp.")

CALLBACK_PRE = """
const secret = pm.collectionVariables.get("webhookSecret");
const body = JSON.stringify({
  eventId: pm.collectionVariables.get("cbEventId") || ("evt-" + Date.now()),
  type: "refund.succeeded",
  idempotencyKey: "refund:" + pm.collectionVariables.get("refundId"),
  amount: 10000,
  currency: "VND",
  reference: "sbx-replay"
});
const t = Math.floor(Date.now() / 1000);
const sig = CryptoJS.HmacSHA256(t + "." + body, secret).toString(CryptoJS.enc.Hex);
pm.collectionVariables.set("cbBody", body);
pm.collectionVariables.set("cbSignature", "t=" + t + ",v1=" + sig);
"""

uc08 = folder("UC-08 Hủy chuyến có phí", [
    advance("[No-show] Tài xế đi đón", "start-pickup", "driverAToken", "PICKING_UP", trip_var="wsTripId"),
    advance("[No-show] Tài xế đến nơi", "arrive", "driverAToken", "ARRIVED", trip_var="wsTripId"),
    req("[No-show] Xem trước phí CUSTOMER_NO_SHOW", "GET",
        "/api/v1/trips/{{wsTripId}}/cancellation-fee?reason=CUSTOMER_NO_SHOW", token="driverAToken",
        tests=status(200) + "\n" + DATA + """
pm.test("Có phí + ruleVersion", () => {
  pm.expect(d.fee).to.be.above(0);
  pm.expect(d.ruleVersion).to.be.a("number");
});
pm.collectionVariables.set("expectedFee", String(d.fee));"""),
    cancel("[No-show] Tài xế huỷ vì khách không ra", "driverAToken", "CUSTOMER_NO_SHOW", trip_var="wsTripId",
           by="DRIVER"),
    cancel("Huỷ lặp → trả lại đúng trip đã huỷ (idempotent, không tạo phí mới)", "driverAToken", "CUSTOMER_NO_SHOW",
           trip_var="wsTripId", by="DRIVER"),
    payments_for_trip("[No-show] Khách bị thu phí đúng 1 lần, đúng số tiền", "customerToken", "wsTripId", """
const fees = d.filter(p => p.purpose === "CANCELLATION_FEE");
pm.test("Đúng 1 khoản phí huỷ", () => pm.expect(fees.length).to.eql(1));
pm.test("amount = phí xem trước, SUCCEEDED", () => {
  pm.expect(String(fees[0].amount)).to.eql(pm.collectionVariables.get("expectedFee"));
  pm.expect(fees[0].status).to.eql("SUCCEEDED");
});"""),

    *full_booking("[Miễn phí]", "freeTripId"),
    req("[Miễn phí] Xem trước phí CHANGED_MIND → 0", "GET",
        "/api/v1/trips/{{freeTripId}}/cancellation-fee?reason=CHANGED_MIND", token="customerToken",
        tests=status(200) + "\n" + DATA + """
pm.test("fee = 0 trong 120 s đầu", () => pm.expect(d.fee).to.eql(0));
pm.test("Có freeUntil", () => pm.expect(d.freeUntil).to.exist);"""),
    cancel("[Miễn phí] Khách huỷ", "customerToken", "CHANGED_MIND", trip_var="freeTripId", by="CUSTOMER"),
    payments_for_trip("[Miễn phí] Không có khoản thu nào", "customerToken", "freeTripId",
                      '\npm.test("0 payment", () => pm.expect(d.length).to.eql(0));'),

    quote("[Staff] Khách lấy báo giá", "RIDE"),
    book("[Staff] Khách đặt chuyến", trip_var="staffTripId"),
    cancel("[NEG] Staff huỷ với ghi chú < 10 ký tự → bị chặn", "adminToken", "OTHER", trip_var="staffTripId",
           note="ngắn", expect_ok=False),
    cancel("[Staff] Staff huỷ có ghi chú", "adminToken", "OTHER", trip_var="staffTripId",
           note="Khách gọi tổng đài nhờ huỷ hộ", by="STAFF"),
    req("[Staff] Chi tiết trip có ghi chú huỷ (đã audit)", "GET", "/api/v1/admin/trips/{{staffTripId}}",
        token="adminToken", tests=status(200) + "\n" + DATA + """
pm.test("cancelNote", () => pm.expect(d.cancelNote).to.eql("Khách gọi tổng đài nhờ huỷ hộ"));"""),

    req("[Hoàn tiền] Finance hoàn 10 000 cho cước UC-04", "POST", "/api/v1/admin/payments/{{farePaymentId}}/refunds",
        token="adminToken", headers={"Idempotency-Key": "{{refundKey}}"},
        pre='pm.collectionVariables.set("refundKey", "refund-" + Date.now());',
        body={"amount": 10000, "reason": "OVERCHARGE", "note": "E2E hoàn một phần"},
        tests=status_in(200, 201) + "\n" + DATA + '\npm.collectionVariables.set("refundId", d.id);'),
    req("[Hoàn tiền] Gửi lặp cùng key → cùng refund", "POST", "/api/v1/admin/payments/{{farePaymentId}}/refunds",
        token="adminToken", headers={"Idempotency-Key": "{{refundKey}}"},
        body={"amount": 10000, "reason": "OVERCHARGE", "note": "E2E hoàn một phần"},
        tests=status_in(200, 201) + "\n" + DATA + """
pm.test("Cùng refundId", () => pm.expect(d.id).to.eql(pm.collectionVariables.get("refundId")));"""),
    req("[NEG] Cùng key, số tiền khác → 409", "POST", "/api/v1/admin/payments/{{farePaymentId}}/refunds",
        token="adminToken", headers={"Idempotency-Key": "{{refundKey}}"},
        body={"amount": 20000, "reason": "OVERCHARGE"}, tests=status(409)),
    req("[NEG] Hoàn vượt số đã thu → bị chặn", "POST", "/api/v1/admin/payments/{{farePaymentId}}/refunds",
        token="adminToken", headers={"Idempotency-Key": "refund-over-{{runId}}"},
        body={"amount": 999999999, "reason": "OVERCHARGE"}, tests=status4xx()),
    req("[Hoàn tiền] Payment = PARTIALLY_REFUNDED, refundedAmount 10 000", "GET",
        "/api/v1/payments/{{farePaymentId}}", token="customerToken", pre=wait(1500),
        tests=status(200) + "\n" + DATA + """
pm.test("refundedAmount = 10000", () => pm.expect(d.refundedAmount).to.eql(10000));
pm.test("PARTIALLY_REFUNDED", () => pm.expect(d.status).to.eql("PARTIALLY_REFUNDED"));"""),
    req("[Callback] Provider gửi lại refund.succeeded (chữ ký đúng) → không hoàn thêm", "POST",
        "/api/v1/payments/callbacks/SANDBOX", headers={"X-Rhl-Signature": "{{cbSignature}}"},
        pre='pm.collectionVariables.set("cbEventId", "evt-" + Date.now());' + CALLBACK_PRE, raw_body="{{cbBody}}",
        tests=status(200) + "\n" + DATA + """
pm.test("Không áp dụng lại", () => pm.expect(["ALREADY_RESOLVED", "DUPLICATE"]).to.include(d.outcome));"""),
    req("[Callback] Replay đúng eventId đó → DUPLICATE", "POST", "/api/v1/payments/callbacks/SANDBOX",
        headers={"X-Rhl-Signature": "{{cbSignature}}"}, pre=CALLBACK_PRE, raw_body="{{cbBody}}",
        tests=status(200) + "\n" + DATA + '\npm.test("DUPLICATE", () => pm.expect(d.outcome).to.eql("DUPLICATE"));'),
    req("[NEG] Callback chữ ký sai → 401", "POST", "/api/v1/payments/callbacks/SANDBOX",
        headers={"X-Rhl-Signature": "t=1,v1=deadbeef"}, raw_body="{{cbBody}}", tests=status(401)),
    req("[Hoàn tiền] refundedAmount vẫn 10 000", "GET", "/api/v1/payments/{{farePaymentId}}", token="customerToken",
        tests=status(200) + "\n" + DATA + '\npm.test("Không đổi", () => pm.expect(d.refundedAmount).to.eql(10000));'),
    req("[Ví] Finance trừ lại 8 000 của tài xế A (REFUND_CLAWBACK)", "POST",
        "/api/v1/admin/wallets/{{driverAId}}/adjustments", token="adminToken",
        headers={"Idempotency-Key": "{{adjKey}}"}, pre='pm.collectionVariables.set("adjKey", "adj-" + Date.now());',
        raw_body='{\n  "amount": -8000,\n  "reason": "REFUND_CLAWBACK",\n  "note": "Thu hồi phần hoàn tiền",\n  "refundId": "{{refundId}}"\n}',
        tests=status_in(200, 201)),
    req("[Ví] Gửi lặp cùng key → không trừ 2 lần", "POST", "/api/v1/admin/wallets/{{driverAId}}/adjustments",
        token="adminToken", headers={"Idempotency-Key": "{{adjKey}}"},
        raw_body='{\n  "amount": -8000,\n  "reason": "REFUND_CLAWBACK",\n  "note": "Thu hồi phần hoàn tiền",\n  "refundId": "{{refundId}}"\n}',
        tests=status_in(200, 201)),
    req("[Ví] Số dư A giảm đúng 8 000 (+ phí no-show nếu có)", "GET", "/api/v1/wallets/me?limit=100",
        token="driverAToken", tests=status(200) + "\n" + DATA + """
pm.test("Chỉ 1 dòng điều chỉnh -8000", () => pm.expect(d.entries.filter(e => e.amount === -8000).length).to.eql(1));
console.log("Số dư trước UC-08:", pm.collectionVariables.get("walletBalanceA"), "→ hiện tại:", d.balance);"""),
], desc="Callback cần biến webhookSecret = PAYMENT_WEBHOOK_SECRET của payment-service (mặc định dev đã điền sẵn).")

uc09 = folder("UC-09 Vị trí tài xế", [
    online("Tài xế B bật online (KHÔNG gửi vị trí)", "driverBToken", "vehicleBId"),
    report_location("A gửi vị trí → CURRENT", "driverAToken", *NEAR_PICKUP, seq_var="seqN"),
    report_location("Gửi lại cùng seq → DUPLICATE", "driverAToken", *NEAR_PICKUP,
                    seq_expr='pm.collectionVariables.get("seqN")', expect_outcome="DUPLICATE"),
    report_location("seq nhỏ hơn → bị bỏ qua, DUPLICATE (vị trí không lùi)", "driverAToken", 10.76, 106.69,
                    seq_expr='Number(pm.collectionVariables.get("seqN")) - 1', expect_outcome="DUPLICATE"),
    report_location("seq mới, giờ thiết bị lùi 5 s → OUT_OF_ORDER", "driverAToken", 10.76, 106.69,
                    seq_expr='Number(pm.collectionVariables.get("seqN")) + 1',
                    ts_expr="new Date(Date.now() - 5000).toISOString()", expect_outcome="OUT_OF_ORDER"),
    report_location("Bản tin gửi muộn 10 phút (mất mạng) → BACKFILL, chỉ vào lịch sử", "driverAToken", 10.76, 106.69,
                    seq_expr='Number(pm.collectionVariables.get("seqN")) + 2',
                    ts_expr="new Date(Date.now() - 600e3).toISOString()", expect_outcome="BACKFILL"),
    req("Vị trí hiện tại vẫn là seq N", "GET", "/api/v1/locations/me", token="driverAToken",
        tests=status(200) + "\n" + DATA + """
pm.test("sequence = N", () => pm.expect(String(d.sequence)).to.eql(pm.collectionVariables.get("seqN")));"""),
    report_location("Độ chính xác 120 m → LOW_ACCURACY", "driverAToken", *NEAR_PICKUP, accuracy=120,
                    expect_outcome="LOW_ACCURACY"),
    report_location("Nhảy ra Hà Nội sau 1 s → SUSPICIOUS", "driverAToken", 21.0285, 105.8542,
                    expect_outcome="SUSPICIOUS"),
    report_location("[NEG] deviceTimestamp lệch 1 giờ tương lai → bị chặn", "driverAToken", *NEAR_PICKUP,
                    ts_expr="new Date(Date.now() + 3600e3).toISOString()", expect_status="4xx"),
    report_location("[NEG] Khách gửi vị trí → 403", "customerToken", *NEAR_PICKUP, expect_status=403),
    req("[NEG] Khách đọc vị trí tài xế → 403", "GET", "/api/v1/locations/me", token="customerToken",
        tests=status(403)),
    offline("A tắt online", "driverAToken"),
    report_location("[NEG] A offline gửi vị trí → bị chặn", "driverAToken", *NEAR_PICKUP, expect_status="4xx"),
    quote("Khách lấy báo giá", "RIDE"),
    book("Khách đặt chuyến (A offline, B online nhưng không có vị trí)", trip_var="noDriverTripId"),
    req("Đợi 35 s → NO_DRIVER (không ai lọt vào matching)", "GET", "/api/v1/trips/{{noDriverTripId}}",
        token="customerToken", pre=wait(35000), tests=status(200) + "\n" + DATA + """
pm.test("NO_DRIVER", () => pm.expect(d.status).to.eql("NO_DRIVER"));"""),
    req("B không nhận offer nào", "GET", "/api/v1/offers", token="driverBToken", tests=status(200) + "\n" + DATA + """
pm.test("Không offer cho trip", () =>
  pm.expect((d || []).some(o => o.tripId === pm.collectionVariables.get("noDriverTripId"))).to.eql(false));"""),
    offline("Dọn dẹp: B tắt online", "driverBToken"),
], desc="Tải 100 tài xế × 3 s và đo p95 ≤ 500 ms không làm được bằng Postman; xem README.")

variables = [
    ("baseUrl", "http://localhost:8080"),
    ("wsUrl", "ws://localhost:8085/ws"),
    ("adminIdentifier", "admin@rhl.local"),
    ("adminPassword", "admin-password-123"),
    ("password", "Passw0rd!E2e"),
    ("webhookSecret", "dev-only-webhook-secret-change-me"),
    ("otpDriverA", ""),
    ("otpDriverB", ""),
]

collection = {
    "info": {
        "_postman_id": str(uuid.uuid5(uuid.NAMESPACE_URL, "rhl-e2e-collection")),
        "name": "RHL E2E – UC-01…UC-09",
        "description": "E2E qua api-gateway (8080). Chạy theo thứ tự thư mục 00 → UC-09. Xem postman/README.md.",
        "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
    },
    "item": [setup, uc01, uc02, uc03, uc04, uc05, uc06, uc07, uc08, uc09],
    "variable": [{"key": k, "value": v, "type": "string"} for k, v in variables],
}

with open(OUT, "w", encoding="utf-8", newline="\n") as f:
    json.dump(collection, f, ensure_ascii=False, indent=2)
    f.write("\n")

count = sum(len(fd["item"]) for fd in collection["item"])
print("requests:", count)
