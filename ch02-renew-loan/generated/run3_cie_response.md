Here's an implementation of `renewLoan` for Bookline. The important subtlety in INV-10 is that holds attach to a **Title**, never a Copy — so the renewal check has to walk from the barcode up to the copy's title and look for unsatisfied holds there. Checking holds "on the copy" would silently pass the exact case your test asks for.

No new dependencies: plain Python with `unittest` from the standard library.

```python
# bookline/circulation.py
"""Renewal logic for Bookline (Ardara County Library Service).

Domain recap:
  - A Loan is of a Copy (identified by barcode) to a Member.
  - A Hold is placed on a Title, never on a Copy.
  - INV-10: a renewal is refused while any unsatisfied hold exists
    on the title of the copy being renewed.
"""

from dataclasses import dataclass, field
from datetime import date, timedelta
from typing import Optional

RENEWAL_LIMIT = 2
RENEWAL_PERIOD_DAYS = 21


@dataclass
class Title:
    title_id: str
    name: str


@dataclass
class Copy:
    barcode: str
    title_id: str


@dataclass
class Hold:
    hold_id: str
    title_id: str          # holds are on Titles, never Copies (domain rule)
    membership_number: str
    satisfied: bool = False


@dataclass
class Loan:
    loan_id: str
    barcode: str
    membership_number: str
    due_date: date
    renewal_count: int = 0
    returned: bool = False


@dataclass
class RenewalResult:
    renewed: bool
    reason: Optional[str] = None
    new_due_date: Optional[date] = None


class CirculationService:
    def __init__(self, copies, loans, holds, today=date.today):
        self._copies = {c.barcode: c for c in copies}
        self._loans = list(loans)
        self._holds = list(holds)
        self._today = today

    # -- queries -----------------------------------------------------------

    def _active_loan_for(self, barcode: str) -> Optional[Loan]:
        for loan in self._loans:
            if loan.barcode == barcode and not loan.returned:
                return loan
        return None

    def _unsatisfied_holds_on_title(self, title_id: str) -> list:
        return [h for h in self._holds
                if h.title_id == title_id and not h.satisfied]

    # -- commands ----------------------------------------------------------

    def renew_loan(self, barcode: str, membership_number: str) -> RenewalResult:
        copy = self._copies.get(barcode)
        if copy is None:
            return RenewalResult(False, reason="unknown-barcode")

        loan = self._active_loan_for(barcode)
        if loan is None:
            return RenewalResult(False, reason="no-active-loan")

        if loan.membership_number != membership_number:
            return RenewalResult(False, reason="not-borrower")

        if loan.renewal_count >= RENEWAL_LIMIT:
            return RenewalResult(False, reason="renewal-limit-reached")

        # INV-10: refuse while any unsatisfied hold exists on the TITLE.
        # Note: we deliberately resolve barcode -> copy -> title_id and
        # check holds against the title, because holds never reference
        # a copy or a barcode.
        if self._unsatisfied_holds_on_title(copy.title_id):
            return RenewalResult(False, reason="unsatisfied-hold-on-title")

        # All checks passed: mutate only now, so refusals change nothing.
        loan.renewal_count += 1
        loan.due_date = self._today() + timedelta(days=RENEWAL_PERIOD_DAYS)
        return RenewalResult(True, new_due_date=loan.due_date)
```

```python
# tests/test_renewal.py
import unittest
from datetime import date

from bookline.circulation import (
    CirculationService, Copy, Hold, Loan, RENEWAL_LIMIT,
)

TODAY = date(2026, 8, 9)


def make_service(copies, loans, holds):
    return CirculationService(copies, loans, holds, today=lambda: TODAY)


class RenewLoanTests(unittest.TestCase):

    def test_refused_when_title_has_hold_even_though_copy_is_not_referenced(self):
        """INV-10, the case that catches copy-level checks.

        The hold references the TITLE (T1) only. Nothing anywhere points
        at barcode B-100. An implementation that looked for holds 'on the
        copy' would find none and wrongly renew.
        """
        copy = Copy(barcode="B-100", title_id="T1")
        loan = Loan(loan_id="L1", barcode="B-100",
                    membership_number="M-42", due_date=date(2026, 8, 15))
        hold = Hold(hold_id="H1", title_id="T1",
                    membership_number="M-99", satisfied=False)

        svc = make_service([copy], [loan], [hold])
        result = svc.renew_loan("B-100", "M-42")

        self.assertFalse(result.renewed)
        self.assertEqual(result.reason, "unsatisfied-hold-on-title")
        # Refusal changes nothing.
        self.assertEqual(loan.renewal_count, 0)
        self.assertEqual(loan.due_date, date(2026, 8, 15))

    def test_satisfied_hold_does_not_block_renewal(self):
        copy = Copy(barcode="B-100", title_id="T1")
        loan = Loan(loan_id="L1", barcode="B-100",
                    membership_number="M-42", due_date=date(2026, 8, 15))
        hold = Hold(hold_id="H1", title_id="T1",
                    membership_number="M-99", satisfied=True)

        svc = make_service([copy], [loan], [hold])
        result = svc.renew_loan("B-100", "M-42")

        self.assertTrue(result.renewed)
        self.assertEqual(loan.renewal_count, 1)

    def test_hold_on_a_different_title_does_not_block(self):
        copy = Copy(barcode="B-100", title_id="T1")
        loan = Loan(loan_id="L1", barcode="B-100",
                    membership_number="M-42", due_date=date(2026, 8, 15))
        hold = Hold(hold_id="H1", title_id="T2",
                    membership_number="M-99", satisfied=False)

        svc = make_service([copy], [loan], [hold])
        result = svc.renew_loan("B-100", "M-42")

        self.assertTrue(result.renewed)

    def test_renewal_limit_enforced_and_nothing_changes_on_refusal(self):
        copy = Copy(barcode="B-100", title_id="T1")
        loan = Loan(loan_id="L1", barcode="B-100",
                    membership_number="M-42", due_date=date(2026, 8, 15),
                    renewal_count=RENEWAL_LIMIT)

        svc = make_service([copy], [loan], [])
        result = svc.renew_loan("B-100", "M-42")

        self.assertFalse(result.renewed)
        self.assertEqual(result.reason, "renewal-limit-reached")
        self.assertEqual(loan.renewal_count, RENEWAL_LIMIT)
        self.assertEqual(loan.due_date, date(2026, 8, 15))

    def test_only_the_borrower_may_renew(self):
        copy = Copy(barcode="B-100", title_id="T1")
        loan = Loan(loan_id="L1", barcode="B-100",
                    membership_number="M-42", due_date=date(2026, 8, 15))

        svc = make_service([copy], [loan], [])
        result = svc.renew_loan("B-100", "M-7")

        self.assertFalse(result.renewed)
        self.assertEqual(result.reason, "not-borrower")

    def test_successful_renewal_extends_due_date(self):
        copy = Copy(barcode="B-100", title_id="T1")
        loan = Loan(loan_id="L1", barcode="B-100",
                    membership_number="M-42", due_date=date(2026, 8, 15))

        svc = make_service([copy], [loan], [])
        result = svc.renew_loan("B-100", "M-42")

        self.assertTrue(result.renewed)
        self.assertEqual(result.new_due_date, date(2026, 8, 30))
        self.assertEqual(loan.renewal_count, 1)


if __name__ == "__main__":
    unittest.main()
```

A few notes on the design choices:

- **Title-level hold check.** `renew_loan` resolves `barcode -> Copy -> title_id`, then asks for unsatisfied holds on that `title_id`. The `Hold` dataclass has no barcode or copy field at all, so a copy-level check is unrepresentable here — which is the safest way to encode the domain rule.
- **Refusals are pure.** All validation happens before any mutation; the loan's `renewal_count` and `due_date` are only touched after every check passes. The first and fourth tests assert this explicitly.
- **Ordering of checks.** The renewal-limit check runs before the hold check, so a member at the limit sees `renewal-limit-reached` rather than a hold message that would disappear once the hold is satisfied. If your product copy prefers the opposite priority, swap the two blocks — the state-change guarantee is unaffected.
- **The requested test** is `test_refused_when_title_has_hold_even_though_copy_is_not_referenced`: the copy `B-100` is referenced by no hold anywhere, the title `T1` has one unsatisfied hold, and the renewal must be refused with the reason surfaced and no state change.

Run with `python -m unittest tests/test_renewal.py`.
