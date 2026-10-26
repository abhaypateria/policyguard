"""
Generates the two versions of the FICTIONAL "Demo Bank Ltd." internal KYC SOP used in the demo corpus.

Why fictional documents exist in the corpus:
  * Real internal bank policies are confidential, so we cannot use one.
  * Two versions (2023, 2024) let us demonstrate and measure as-of-date (temporal) answering.
  * A few rules deliberately DISAGREE with the real RBI Master Direction, to demonstrate and
    measure conflict detection - the kind of policy drift a compliance team must catch.

Run:  python tools/make_demo_sop.py      (requires: pip install pymupdf)
"""
from pathlib import Path

import pymupdf

OUT = Path(__file__).resolve().parent.parent / "src" / "main" / "resources" / "corpus"

BANNER = ("<p class='banner'>FICTIONAL DOCUMENT - created for the PolicyGuard academic demonstration. "
          "Demo Bank Ltd. is not a real bank. Rules marked here may intentionally differ from RBI directions.</p>")

CSS = """
body { font-family: sans-serif; font-size: 10.5pt; line-height: 1.35; }
h1 { font-size: 16pt; margin-bottom: 2pt; }
h2 { font-size: 12.5pt; margin-top: 12pt; margin-bottom: 3pt; }
p, li { margin-top: 2pt; margin-bottom: 3pt; }
.meta { font-size: 9.5pt; color: #444; }
.banner { font-size: 9pt; color: #9a1c1c; border: 1px solid #9a1c1c; padding: 4pt; }
"""

COMMON_SCOPE = """
<h2>1. Purpose and Scope</h2>
<p>This Standard Operating Procedure (SOP) sets out how branches of Demo Bank Ltd. identify and verify customers,
open accounts and keep customer due diligence (CDD) information up to date. It applies to all branches, the
NRI Services Desk and the Central Account Opening Cell. It must be read together with the RBI Master Direction
on Know Your Customer (KYC); where this SOP is silent, the RBI Master Direction applies.</p>

<h2>2. Customer Acceptance and Risk Categorisation</h2>
<p>Every customer is placed in one of three risk categories - low, medium or high - based on identity, social and
financial status, nature of business activity and the customer's location. Politically Exposed Persons (PEPs),
customers with large unexplained cash transactions and non-face-to-face customers are categorised as high risk.</p>
<p>Opening of an account for a high-risk customer shall be approved by the Branch Manager. Opening of an account
for a Politically Exposed Person shall additionally be approved by the Compliance Head.</p>

<h2>3. Documents for Account Opening - Resident Individuals</h2>
<p>A resident individual shall submit: (a) one Officially Valid Document (OVD) - passport, driving licence, proof of
possession of Aadhaar number, Voter's Identity Card, NREGA job card or National Population Register letter;
(b) the Permanent Account Number (PAN) or Form 60; and (c) one recent passport-size photograph. In a joint account,
these documents are required from every account holder.</p>
"""


def v2023() -> str:
    return f"""
<h1>Demo Bank Ltd. - Internal SOP: KYC, Account Opening and NRI Accounts</h1>
<p class='meta'>Document reference DB/COMP/SOP/KYC/2023 &nbsp;|&nbsp; Version 2023 &nbsp;|&nbsp;
Effective from 1 April 2023 &nbsp;|&nbsp; Owner: Compliance Department</p>
{BANNER}
{COMMON_SCOPE}
<p>The account shall be opened within 3 working days of receipt of complete documentation.</p>

<h2>4. Video-based Customer Identification Process (V-CIP)</h2>
<p>Demo Bank has not yet implemented V-CIP. Accounts shall not be opened through video-based KYC until further
notice; all account opening requires in-person verification of the customer at a branch.</p>

<h2>5. NRI Accounts - NRE and NRO</h2>
<p>A Non-Resident Indian (NRI) opening an NRE or NRO account shall submit: a valid Indian passport; a valid visa,
work permit or residence permit of the country of residence; proof of overseas address not older than three
months; PAN or Form 60; and two recent photographs.</p>
<p>Copies of documents of a customer residing abroad shall be certified by an overseas branch of a scheduled
commercial bank in India, the Indian Embassy or Consulate, or a notary public of the country of residence.</p>
<p>The minimum initial deposit for an NRE savings account is Rs. 10,000. An NRO account may be held jointly with a
resident relative on a "former or survivor" basis. NRI account applications shall be processed within
5 working days.</p>

<h2>6. Periodic Updation of KYC</h2>
<p>KYC of existing customers shall be updated periodically as follows: high-risk customers every 2 years;
medium-risk customers every 8 years; low-risk customers every 12 years, counted from the date of account
opening or the last KYC updation.</p>

<h2>7. Upload to the Central KYC Records Registry (CKYCR)</h2>
<p>The Central Account Opening Cell shall upload the KYC records of every new customer to the CKYCR within
15 days of opening of the account.</p>

<h2>8. Record Retention</h2>
<p>Records of customer transactions shall be preserved for at least five years from the date of transaction, and
identification records for at least five years after the business relationship has ended.</p>

<h2>9. Cash Transactions and Payment Instruments</h2>
<p>Cash deposits of Rs. 50,000 or more require the customer's PAN or Form 60. Demand drafts, pay orders and
travellers cheques of Rs. 50,000 and above shall be issued only by debit to the customer's account or against
a cheque, and not against cash.</p>

<h2>10. Escalation</h2>
<p>Any question not covered by this SOP shall be referred to the Compliance Desk before the customer is
onboarded. Staff must not rely on informal guidance.</p>
"""


def v2024() -> str:
    return f"""
<h1>Demo Bank Ltd. - Internal SOP: KYC, Account Opening and NRI Accounts</h1>
<p class='meta'>Document reference DB/COMP/SOP/KYC/2024 &nbsp;|&nbsp; Version 2024 &nbsp;|&nbsp;
Effective from 1 July 2024 &nbsp;|&nbsp; Supersedes SOP version 2023 &nbsp;|&nbsp; Owner: Compliance Department</p>
{BANNER}
{COMMON_SCOPE}
<p>The account shall be opened within 2 working days of receipt of complete documentation.</p>

<h2>4. Video-based Customer Identification Process (V-CIP)</h2>
<p>Demo Bank has implemented V-CIP. Resident individual customers may open savings accounts through V-CIP. The
V-CIP session shall be conducted by a trained official of the bank's designated V-CIP centre, and the complete
session shall be recorded and stored. V-CIP is not available for NRI accounts.</p>

<h2>5. NRI Accounts - NRE and NRO</h2>
<p>A Non-Resident Indian (NRI) opening an NRE or NRO account shall submit: a valid Indian passport, or a foreign
passport together with an OCI card for a Person of Indian Origin; a valid visa, work permit or residence permit of
the country of residence; proof of overseas address not older than three months; PAN or Form 60; and two recent
photographs.</p>
<p>Copies of documents of a customer residing abroad shall be certified by an overseas branch of a scheduled
commercial bank in India, the Indian Embassy or Consulate, or a notary public of the country of residence.</p>
<p>The minimum initial deposit for an NRE savings account is Rs. 25,000. An NRO account may be held jointly with a
resident relative on a "former or survivor" basis. NRI account applications shall be processed within
3 working days.</p>

<h2>6. Periodic Updation of KYC</h2>
<p>Revised to align with the RBI Master Direction on KYC. KYC of existing customers shall be updated periodically
as follows: high-risk customers every 2 years; medium-risk customers every 8 years; low-risk customers every
10 years, counted from the date of account opening or the last KYC updation.</p>

<h2>7. Upload to the Central KYC Records Registry (CKYCR)</h2>
<p>The Central Account Opening Cell shall upload the KYC records of every new customer to the CKYCR within
15 days of opening of the account.</p>

<h2>8. Record Retention</h2>
<p>Records of customer transactions shall be preserved for at least five years from the date of transaction, and
identification records for at least five years after the business relationship has ended.</p>

<h2>9. Cash Transactions and Payment Instruments</h2>
<p>Cash deposits of Rs. 50,000 or more require the customer's PAN or Form 60. To improve customer convenience,
demand drafts and pay orders of Rs. 50,000 and above may be issued against cash, provided the customer furnishes
PAN.</p>

<h2>10. Inoperative Accounts</h2>
<p>A savings or current account in which there has been no customer-induced transaction for a period of over two
years shall be classified as inoperative. It may be reactivated after the customer updates KYC at any branch.</p>

<h2>11. Escalation</h2>
<p>Any question not covered by this SOP shall be referred to the Compliance Desk before the customer is
onboarded. Staff must not rely on informal guidance.</p>
"""


def write_pdf(html: str, path: Path) -> None:
    story = pymupdf.Story(html=html, user_css=CSS)
    writer = pymupdf.DocumentWriter(str(path))
    mediabox = pymupdf.paper_rect("a4")
    where = mediabox + (54, 54, -54, -54)
    more = True
    while more:
        device = writer.begin_page(mediabox)
        more, _ = story.place(where)
        story.draw(device)
        writer.end_page()
    writer.close()


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    write_pdf(v2023(), OUT / "demobank-kyc-sop-2023.pdf")
    write_pdf(v2024(), OUT / "demobank-kyc-sop-2024.pdf")
    print("Wrote fictional Demo Bank SOPs to", OUT)
