"""Generates representative test documents for the structured-extraction E2E run.

native PDFs are built with PyMuPDF text; "scanned" variants are the same
pages rasterized (with mild noise/blur) and re-embedded as images, so they
have no text layer and must go through PaddleOCR.
"""
import io
import random
import sys
from pathlib import Path

import fitz
from PIL import Image, ImageFilter

OUT = Path(sys.argv[1])
OUT.mkdir(parents=True, exist_ok=True)
random.seed(7)


def text(page, x, y, s, size=11, bold=False):
    page.insert_text((x, y), s, fontsize=size, fontname="hebo" if bold else "helv")


def table(page, x, y, col_widths, rows, row_h=20, header_bold=True):
    total_w = sum(col_widths)
    for r, row in enumerate(rows):
        top = y + r * row_h
        page.draw_rect(fitz.Rect(x, top, x + total_w, top + row_h), width=0.6)
        cx = x
        for c, cell in enumerate(row):
            if c:
                page.draw_line((cx, top), (cx, top + row_h), width=0.6)
            text(page, cx + 4, top + row_h - 6, str(cell), size=10, bold=header_bold and r == 0)
            cx += col_widths[c]
    return y + len(rows) * row_h


def employee_contract(doc):
    page = doc.new_page(width=595, height=842)
    text(page, 190, 70, "EMPLOYMENT CONTRACT", size=18, bold=True)
    text(page, 60, 100, "Oak Integrated Systems (Pvt) Ltd - Human Resources", size=10)
    y = 140
    for label, value in [
        ("Employee Name:", "John Silva"),
        ("Employee ID:", "EMP-1023"),
        ("Department:", "Finance"),
        ("Designation:", "Senior Accountant"),
        ("Joining Date:", "2026-09-01"),
        ("Salary:", "LKR 150,000"),
        ("Reporting Manager:", "Nimal Fernando"),
    ]:
        text(page, 60, y, label, bold=True)
        text(page, 220, y, value)
        y += 22
    y += 12
    text(page, 60, y, "Salary Breakdown", size=13, bold=True)
    y = table(page, 60, y + 10, [220, 140], [["Component", "Monthly Amount (LKR)"], ["Basic Salary", "120,000"], ["Transport Allowance", "20,000"], ["Meal Allowance", "10,000"], ["Total", "150,000"]])
    y += 30
    text(page, 60, y, "Terms and Conditions", size=13, bold=True)
    for line in [
        "1. Probation period: the first six months of employment are probationary.",
        "2. Working hours: 8.30 a.m. to 5.00 p.m., Monday to Friday.",
        "3. Notice period: one month's written notice by either party.",
    ]:
        y += 20
        text(page, 60, y, line, size=10)
    y += 50
    text(page, 60, y, "Employee Signature: ____________", size=10)
    text(page, 330, y, "Date: 25/08/2026", size=10)


def invoice(doc):
    page = doc.new_page(width=595, height=842)
    text(page, 60, 70, "TAX INVOICE", size=20, bold=True)
    text(page, 60, 95, "Lanka Office Supplies Ltd, 45 Galle Road, Colombo 03", size=10)
    left = [("Invoice No", "INV-2026-0457"), ("Invoice Date", "12 September 2026"), ("PO Number", "PO-88213")]
    right = [("Bill To", "Oak Integrated Systems"), ("Customer ID", "CUST-3310"), ("Due Date", "12/10/2026")]
    for i, ((l1, v1), (l2, v2)) in enumerate(zip(left, right)):
        yy = 140 + i * 22
        text(page, 60, yy, l1, bold=True)
        text(page, 150, yy, v1)
        text(page, 320, yy, l2, bold=True)
        text(page, 410, yy, v2)
    y = table(page, 60, 230, [40, 220, 60, 80, 80], [
        ["No", "Description", "Qty", "Unit Price", "Amount"],
        ["1", "A4 Copy Paper 80gsm (box)", "10", "4,500.00", "45,000.00"],
        ["2", "Toner Cartridge HP 26A", "2", "18,750.00", "37,500.00"],
        ["3", "Stapler Heavy Duty", "3", "2,250.00", "6,750.00"],
    ])
    for i, (label, value) in enumerate([("Subtotal", "89,250.00"), ("VAT 18%", "16,065.00"), ("Total Due", "LKR 105,315.00")]):
        text(page, 340, y + 25 + i * 20, label, bold=True)
        text(page, 440, y + 25 + i * 20, value)
    text(page, 60, y + 110, "Payment Terms: 30 days from invoice date. Bank: Commercial Bank, A/C 1002-3344-55", size=10)


def statement(doc):
    rows = [["Date", "Description", "Reference", "Debit", "Credit", "Balance"]]
    balance = 250000.0
    for i in range(1, 76):
        amount = round(random.uniform(500, 25000), 2)
        credit = i % 4 == 0
        balance += amount if credit else -amount
        rows.append([f"2026-08-{(i % 28) + 1:02d}", random.choice(["POS PURCHASE", "ATM WITHDRAWAL", "SALARY CREDIT", "UTILITY BILL", "FUND TRANSFER"]),
                     f"TXN{100000 + i}", "" if credit else f"{amount:,.2f}", f"{amount:,.2f}" if credit else "", f"{balance:,.2f}"])
    per_page = 30
    for p, start in enumerate(range(1, len(rows), per_page)):
        page = doc.new_page(width=595, height=842)
        if p == 0:
            text(page, 60, 60, "ACCOUNT STATEMENT", size=16, bold=True)
            for i, (l, v) in enumerate([("Account Holder:", "Oak Integrated Systems"), ("Account Number:", "1002-3344-55"), ("Statement Period:", "01/08/2026 - 31/08/2026")]):
                text(page, 60, 90 + i * 18, l, bold=True)
                text(page, 180, 90 + i * 18, v)
            top = 160
        else:
            text(page, 60, 60, f"Account Statement (continued) - page {p + 1}", size=10)
            top = 80
        table(page, 40, top, [65, 120, 75, 80, 80, 95], [rows[0]] + rows[start:start + per_page], row_h=19)


def to_scan(pdf_bytes, dpi=150, noise=True):
    src = fitz.open(stream=pdf_bytes, filetype="pdf")
    images = []
    for page in src:
        pix = page.get_pixmap(dpi=dpi)
        img = Image.frombytes("RGB", (pix.width, pix.height), pix.samples)
        if noise:
            img = img.rotate(0.6, fillcolor="white", expand=False).filter(ImageFilter.GaussianBlur(0.6))
            px = img.load()
            for _ in range(img.width * img.height // 400):
                x, y = random.randrange(img.width), random.randrange(img.height)
                px[x, y] = (150, 150, 150)
        images.append(img)
    return images


def save_pdf(builder, name):
    doc = fitz.open()
    builder(doc)
    data = doc.tobytes()
    (OUT / name).write_bytes(data)
    return data


def image_only_pdf(images, name):
    doc = fitz.open()
    for img in images:
        buf = io.BytesIO()
        img.save(buf, format="PNG")
        page = doc.new_page(width=595, height=842)
        page.insert_image(page.rect, stream=buf.getvalue())
    (OUT / name).write_bytes(doc.tobytes())


contract = save_pdf(employee_contract, "employee_contract_native.pdf")
to_scan(contract)[0].save(OUT / "employee_contract_scan.png")
inv = save_pdf(invoice, "_invoice_native.pdf")
image_only_pdf(to_scan(inv), "invoice_scanned.pdf")
(OUT / "_invoice_native.pdf").unlink()
save_pdf(statement, "bank_statement_3pages_native.pdf")
Image.new("RGB", (1240, 1754), "white").save(OUT / "blank_page.png")
(OUT / "corrupted.pdf").write_bytes(contract[: len(contract) // 3])
(OUT / "renamed_exe.pdf").write_bytes(b"MZ\x90\x00\x03\x00\x00\x00" + bytes(200))
print("\n".join(sorted(p.name for p in OUT.iterdir())))
