# Mini POS

[![CI](https://github.com/jaisonjacob2000/MiniPOS/actions/workflows/ci.yml/badge.svg)](https://github.com/jaisonjacob2000/MiniPOS/actions/workflows/ci.yml)

An Android point-of-sale app in Kotlin and Jetpack Compose that prints real receipts on a
Bluetooth thermal printer. It is a small model of in-store restaurant technology: a POS
terminal, the peripherals it drives, and what happens when the hardware misbehaves.

## What it does today

- Take an order on a tablet or phone layout (menu grid, cart, subtotal, tax, total).
- **Pay & Print** sends the receipt to the selected printer:
  - **Simulated printer**: runs anywhere, including the emulator, and shows the receipt on screen.
  - **Bluetooth ESC/POS printer**: any paired 58 mm or 80 mm thermal printer that speaks ESC/POS.
  - **Vendor-SDK printer** (`printer/acme`): a simulated, callback-style third-party SDK wrapped
    behind the same interface. It exists in code and tests; it is not yet selectable in the UI.
- **Kitchen ticket**: after the customer receipt, a slip prints with a large order number and
  the items, no prices.
- Live printer status in the top bar (disconnected, connecting, ready, printing, error).
- **A failed print never loses the order.** If the printer is off or out of paper, the order
  stays on screen and the cashier can fix the printer and try again.

## Architecture

```
PosScreen (Compose UI)
   │
PosViewModel (Android: coroutine scope, Bluetooth system services)
   │
PosController (plain Kotlin, no Android, fully unit tested)
   ├── Order / Money             cents-based totals, immutable order
   ├── ReceiptFormatter → Receipt → EscPosEncoder → ESC/POS bytes
   └── ReceiptPrinter (interface)
         ├── FakeReceiptPrinter       emulator demos and tests, can simulate failures
         ├── BluetoothEscPosPrinter   real hardware over Bluetooth SPP
         └── AcmeSdkPrinter           adapter around a callback-based vendor SDK (simulated)
```

### Design decisions

- **Every device sits behind one interface.** Supporting a new printer (network, USB, a
  vendor SDK) means writing one class that implements `ReceiptPrinter`. The POS logic does not change.
- **The business logic has no Android dependencies.** `PosController` runs on the plain JVM,
  so checkout, failure handling and printer switching are covered by fast unit tests.
- **ESC/POS is encoded by hand** (`EscPosEncoder`) rather than through a library, so each
  command (`ESC @` initialize, `ESC a` align, `GS !` size, `GS V` cut) is visible and tested.
- **Bluetooth writes are chunked** with a short pause. Many inexpensive printers have small
  buffers and drop bytes if a whole receipt arrives at once.
- **Money is stored in cents**, and tax is rounded half up, so totals never drift.
- **A checkout can't lock the cashier out.** The "printing" lock is claimed atomically
  (`compareAndSet`) and released on a printer error, an exception from a printer, and
  coroutine cancellation (for example the screen closing mid-print). In every case the order
  is kept until a receipt really prints.
- **The receipt completes the sale; the kitchen ticket can wait.** If the customer receipt
  fails, nothing changes and the order is kept. If the receipt prints but the ticket fails,
  the sale stays complete (reprinting the receipt would give the customer a duplicate), and
  the ticket waits in a queue with a "Reprint kitchen ticket" button. The next successful
  checkout also catches the queue up, oldest first, so tickets never reach the kitchen out
  of order.
- **Third-party SDKs are wrapped, not leaked.** `AcmeSdkPrinter` bridges a callback API to
  `suspend` functions, adds a timeout so a silent SDK can't hang checkout, ignores duplicate
  callbacks, reports exceptions instead of throwing, aborts the SDK on cancellation, and
  translates vendor codes to app errors (unknown future codes become `UNKNOWN`, with the code
  kept for logs). It must pass the same `PrinterContractTest` as every other printer.

## Running it

1. Open the project in Android Studio and let Gradle sync. If Studio offers to upgrade the
   Android Gradle Plugin, accept it.
2. Run on an emulator (a Pixel Tablet image shows the two-pane layout). The simulated printer
   is selected by default.
3. To use a real printer: pair it in Android's Bluetooth settings, tap the printer chip in the
   top bar, allow Bluetooth access, and pick it from the list.

Run the unit tests:

```
./gradlew testDebugUnitTest
```

## Roadmap

- [x] **Phase 1: POS and receipt printer.** Order entry, ESC/POS encoding, simulated and
      Bluetooth printers, failure handling, unit tests.
- [x] Kitchen ticket printed alongside the customer receipt.
- [x] CI on GitHub Actions running the unit tests on every push.
- [ ] **Phase 2: Kitchen display (KDS) that works offline.** A second device finds the POS on
      the local network with Android network service discovery (NSD) and receives orders
      through a small web server embedded in the app, with no internet connection.
- [ ] **Phase 3: Device matrix testing.** Instrumented tests across several emulator
      configurations (Android versions and screen sizes) in CI.
- [ ] Barcode scanning (ML Kit or a USB HID scanner) to add items.


## Testing

65 JVM unit tests run without a device or printer (`./gradlew testDebugUnitTest`).

| Suite | What it proves |
| --- | --- |
| `KitchenTicketTest` | The slip has no prices and fits both paper widths; if the ticket fails after the receipt printed the sale stays complete and the ticket waits; backlog prints in order; closing the screen mid-ticket does not undo the sale |
| `CheckoutRobustnessTest` | A cancelled print releases the lock, a printer that throws keeps the order, 64 simultaneous taps print once |
| `ByteChunksTest` | The Bluetooth chunking logic, tested without Bluetooth |
| `AcmeSdkPrinterContractTest` | The vendor-SDK printer obeys the same rules as every printer |
| `AcmeSdkPrinterTest` | Code translation, threads, timeout on a silent SDK, duplicate callbacks, replies before the call returns, SDK exceptions, cancellation, full checkout |
| `OrderTest` | Cents-based totals, quantity changes, tax rounding (half up) |
| `ReceiptTest` | Every line fits the paper, long names truncate, prices right-aligned |
| `EscPosBytesTest` | Exact ESC/POS bytes for a known receipt, each command annotated |
| `PrinterContractTest` | Rules every printer must follow; new devices subclass it and must pass |
| `PosControllerTest` | Checkout, paper out, reconnect and retry, switching printers |
| `RealWorldCheckoutTest` | Cashier double-tap prints once, every printer error keeps the order, 80 mm paper |

I checked that the tests actually catch bugs. Removing the double-tap guard in
`PosController` makes `doubleTapOnPayAndPrintPrintsOnlyOnce` fail, because two receipts
print with the same order number. The SDK adapter was checked the same way: removing the
once-only reply guard, the abort on cancellation, the exception catch, or the timeout each
makes a specific test fail.

## How this was built

I built this in the week before an interview for a hardware integration role, to learn
Kotlin, Jetpack Compose and Android device integration hands-on.

- **AI-assisted development.** Claude generated the initial scaffold and several of the
  test suites, and later helped write the reliability fixes and the vendor-SDK adapter. I use GitHub Copilot daily at work and Claude on personal projects, and I
  treat AI output like any other code: I read it, run it, and verify it before I own it.
- **Ran and checked it** on Pixel Tablet (two-pane layout) and phone (single-column)
  emulators, including the simulated paper-out flow.
- **Lint triage.** Android lint flagged `UnusedBoxWithConstraintsScope`. I confirmed it was
  a false positive (`maxWidth` picks the layout), made the use explicit, and suppressed
  it with a comment explaining why.
- **Verified the tests** by removing the guard they protect and confirming they fail.

## Known limitations

Found while reviewing the code. These are what I'd fix before this ran in a restaurant:

1. **"Printed" means "bytes sent."** The app never asks the printer whether paper actually
   came out. ESC/POS status queries (`DLE EOT`) can read the paper sensor.
2. **Orders live in memory.** If the app is killed, the open order is lost. A real POS
   saves orders (for example with Room) before printing.
3. **Order numbers reset** to #0001 when the app restarts.
4. **The printer choice isn't saved** between launches.
5. **Non-ASCII text prints as `?`.** Supporting code pages (`ESC t n`) would fix accents
   like "Jalapeño".
6. **The on-screen preview** shows the header at normal size, while the printer prints
   it double-width.
7. **The Bluetooth printer has not been tried on real hardware.** Its chunking logic is
   unit tested, but the Bluetooth class itself has never printed on a real printer.
8. **The vendor SDK is simulated**, and the ViewModel and Compose screens have no automated
   tests yet.
9. **Kitchen tickets print on the same printer as receipts**, and waiting tickets are lost if
   the app is killed. A real store would use a separate kitchen printer and save the queue.
