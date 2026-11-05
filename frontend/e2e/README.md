# End-to-end tests

Playwright specs for the main flows (frontend-design.md §11, stage 5), run against the **running
local stack**. They are not part of CI.

| Spec               | Flow                                                                                                                              |
| ------------------ | --------------------------------------------------------------------------------------------------------------------------------- |
| `ride.spec.ts`     | UC-02 → UC-04: book from a quote in the web app, the driver runs the trip in the web app, both sides follow it live, receipt paid |
| `realtime.spec.ts` | UC-07: the customer loses the network mid-trip, catches up without a reload once it is back                                       |
| `cancel.spec.ts`   | UC-08: the customer cancels an accepted trip after seeing the fee; a repeated cancel charges nothing more                         |
| `staff.spec.ts`    | Every staff section opens for an administrator (read-only)                                                                        |

## Before running

1. Start the stack (README "Chạy local") and the web app: `npm run dev` (port 5173).
2. Have these accounts:
   - a customer;
   - an **approved driver** with an active motorbike (serves RIDE and DELIVERY), and no other driver
     online near District 1, so the offers reach this one;
   - an administrator (`BOOTSTRAP_ADMIN_EMAIL` / `BOOTSTRAP_ADMIN_PASSWORD` of user-service).
3. Set the environment (specs whose accounts are missing are skipped):

```bash
export E2E_CUSTOMER=customer@example.com
export E2E_DRIVER=driver@example.com
export E2E_PASSWORD='…'              # customer and driver
export E2E_ADMIN=admin@rhl.local
export E2E_ADMIN_PASSWORD='…'
# optional: E2E_WEB_URL (http://localhost:5173), E2E_API_URL (http://localhost:8080),
#           E2E_CHANNEL (chrome = the installed Chrome), E2E_HEADED=1
```

## Run

```bash
npm run e2e                    # all specs, one at a time
npx playwright test ride       # one spec
npx playwright show-report     # the last HTML report, with traces of failures
```

The specs book real trips: each run leaves a few completed or cancelled trips, sandbox payments
and possibly a cancellation fee in the local data. Open trips left by an interrupted run are
cancelled at the start of the next one, and the driver is set offline at the end.
