# Build tasks

These are the parts of the project you write yourself. Each one is small enough for an
evening, maps to something in the job description, and gives you a concrete story for the
interview.

## Task A: Kitchen ticket (Tuesday)

In a restaurant, the kitchen gets its own ticket: big order number, items and quantities,
no prices.

1. In `ReceiptFormatter`, add:

   ```kotlin
   fun kitchenTicket(order: Order, orderNumber: Int, timestamp: String): Receipt
   ```

   Use `large = true` for the order number and the item lines. Large text is double width,
   so a 32-character printer fits only 16 large characters per line. Make sure long item
   names still fit.

2. In `PosController.checkout()`, print the kitchen ticket after the customer receipt
   succeeds (same printer for now).

3. Decide what happens if the receipt prints but the kitchen ticket fails. The customer has
   paid, so clearing the order is probably right, but the cashier needs to know the kitchen
   never got it. Pick a behavior, implement it, and be ready to explain why.

4. Add tests in `ReceiptTest` and `PosControllerTest`:
   - The kitchen ticket contains the order number and every item, and no `$`.
   - A successful checkout produces two print jobs (`printer.printedJobs.size == 2`).
   - Your answer to step 3, using `failNextPrint`. Tip: you may need a way to fail the
     *second* print rather than the next one.

## Task B: CI on GitHub Actions (Wednesday)

Add `.github/workflows/ci.yml` that runs on every push and pull request:

- `actions/checkout`
- `actions/setup-java` with Temurin 17
- `gradle/actions/setup-gradle`
- `./gradlew testDebugUnitTest`

Add the workflow's status badge to the top of the README.

## Task C: Retry on connection blips (Wednesday)

Bluetooth connections drop. A paper-out error needs a human, but a dropped connection
(`PrinterError.IO_ERROR`) often succeeds on a second try.

1. In `PosController.checkout()`, when a print fails with `IO_ERROR`, reconnect and retry
   once before giving up. Do not retry `PAPER_OUT`.
2. Tests: `IO_ERROR` once prints successfully; `PAPER_OUT` fails without a retry.
3. Think about the risk: could a retry ever print the same receipt twice? When would that happen?

## Before the interview

- Push to a public GitHub repo and add a screenshot or short screen recording of the
  emulator: add items, print, simulate paper out, show the order kept, print again.
- Be able to explain every file. If something is unclear, ask Copilot or Claude to walk
  you through it, then check the explanation against the code.
