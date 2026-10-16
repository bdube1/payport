#!/usr/bin/env python3
"""
Generates SYNTHETIC provider statement fixtures for StatementParsersTest.

The layouts (sheets, title rows, header names, column order, cell types, date formats) copy the
sample reports the owner supplied on 2026-10-16. Every value is invented: no customer names,
phone numbers, card numbers, account numbers or references from the real files are used.
Run from the repository root:  python3 scripts/test-fixtures/make_statement_fixtures.py
"""
import datetime as dt
import os

import openpyxl
from reportlab.lib.pagesizes import A4, landscape
from reportlab.pdfgen import canvas

OUT = "src/test/resources/statements"
os.makedirs(OUT, exist_ok=True)


def ecocash():
    wb = openpyxl.Workbook()
    wb.remove(wb.active)
    hdr = ["Transactor", "Name", "Transfer Date", "Transfer Id", "Correlator", "Mobile Number", "Service Name",
           "Transfer Value", "Post Balance", "Transaction Type", "Cr/Dr", "Currency"]
    sheets = {
        "ZWG Transactions": [
            ["700000001", "TEST MERCHANT", "2026-08-19", "OW260819.0001.T0000001", "1000001", "IND0000001", "Automatic Liquidation", "7061.77", "0", "MP", "DR", "ZWG"],
            ["700000001", "TEST MERCHANT", "2026-08-19", "MP260819.0002.T0000002", "PP-WEB-20260819-000101", "771000001", "Online Merchant Payment Request", "22", "22", "MR", "CR", "ZWG"],
        ],
        "USD Transactions": [
            ["700000001", "TEST MERCHANT", "2026-08-19", "MP260819.0003.T0000003", "PP-WEB-20260819-000102-2", "771000002", "Online Merchant Payment Request", "1", "35589.51", "MR", "CR", "USD"],
            ["700000001", "TEST MERCHANT", "2026-08-19", "MP260819.0004.T0000004", "corrABCDEF123456", "771000003", "Online Merchant Payment Request", "1,250.50", "36840.01", "MR", "CR", "USD"],
        ],
    }
    for name, rows in sheets.items():
        ws = wb.create_sheet(name)
        for _ in range(4):
            ws.append([None] * 12)
        ws.append(["EcoCash Account Statements for TESTMERCHANT"] + [None] * 11)
        ws.append([None] * 12)
        ws.append(hdr)
        for r in rows:
            ws.append(r)
    wb.save(f"{OUT}/ecocash.xlsx")


def innbucks():
    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = "export"
    hdr = ["Transaction date and time", "Banking day", "Head Transaction Id", "Operation Type", "Operation Subtype", "Status",
           "Description", "Earned fee description", "Recipient Account Number", "Sender Account Number", "Base amount US$",
           "Fee/Commission Amount US$", "Tax Amount US$", "Posted amount US$", "Running FLOAT balance US$",
           "Running POSTED balance US$", "Terminal label", "Employee name", "Location name", "Payment token",
           "Reversal transaction Id", "Reverted transaction Id", "Comment", "STAN", "RRN", "Card transaction ID",
           "Veengu Card transaction ID", "Payment ID", "External Terminal", "External type"]
    ws.append(hdr)
    def row(when, head, status, amount, token, stan):
        return [when, "2026-08-19", head, "PURCHASE", "", status, "Purchase at Test Merchant", "", "2000000000001",
                "3000000000001", amount, "-0.21", "", amount, "100.00", "100.00", "TERM0001", "Third-party terminal", "TestAPI",
                token, "", "", "PAYMENT TEST", stan, "", "", "", "", "", ""]
    ws.append(row("Aug 19, 2026, 11:18:17 AM", "head-0001", "COMPLETED", "10.3", "500000001", "PP-API-20260819-000201"))
    ws.append(row("Aug 18, 2026, 5:34:31 PM", "head-0002", "FAILED", "10.3", "500000002", "PP-API-20260819-000202"))
    ws.append(row("Aug 5, 2026, 2:57:08 PM", "head-0003", "COMPLETED", "0.5", "500000003", "PP-API-20260819-000203"))
    wb.save(f"{OUT}/innbucks.xlsx")


def netone():
    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = "Sheet1"
    ws.append(["RechargeDate", "MobileNumber", "AgentRefernece", "BrandName", "RechargeStatus", "Amount"])
    ws.append(["Wed Aug 19 23:59:58 SAST 2026", "0710000001", "PP-WEB-20260819-000301", "NetOne USD Pinless", "Success", "8.0000"])
    ws.append(["Wed Aug 19 23:59:54 SAST 2026", "0710000002", "PP-WEB-20260819-000302", "NetOne USD Pinless", "Failed", "0.5000"])
    ws.append(["Wed Aug 19 23:59:44 SAST 2026", "0710000003", "PP-WEB-20260819-000303", "Pinless USD Bundles", "Success", "7.0000"])
    wb.save(f"{OUT}/netone.xlsx")


def onemoney():
    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = "Received Records"
    ws.append(["Trading No.", "Original Trading No.", "Merchant Order No.", "Trading Time", "Transaction Type", "Trading Amount",
               "Payer Name", "Operator Name", "Status", "Settlement Status", None, None])
    ws.append(["MP2608201004000001", None, "PP-MOB-20260820-000401", "20-08-2026 10:04:45", "30", "1.50", "TEST ****", None, "30", "20", "USD", "+"])
    ws.append(["MP2608200751000002", None, "PP-MOB-20260820-000402", "20-08-2026 07:51:10", "30", "1.00", "TEST ****", None, "40", "20", "USD", "+"])
    ws.append(["MP2608200123000003", None, "PP-MOB-20260820-000403", "20-08-2026 01:23:50", "30", "0.50", "TEST ****", None, "30", "20", "USD", "-"])
    ws.append(["MP2608200001000004", None, "PP-MOB-20260820-000404", "20-08-2026 00:01:06", "30", "0.20", "TEST ****", None, "30", "20", "ZWG", "+"])
    wb.save(f"{OUT}/onemoney.xlsx")


def telecel():
    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = "Top Up Transactions"
    ws.append(["agent_code", "dest_mobile", "agent_id", "airwide_tid", "amount", "agent_opening_balance", "agent_closing_balance",
               "result_description", "request_time", "response_time", "response_code"])
    ws.append([730000001, 733000001, "PPWEB20260819000501", 20000001, -56, 15187.7405, 15131.7405, "Transaction Successful",
               dt.datetime(2026, 8, 19, 18, 10, 54), dt.datetime(2026, 8, 19, 18, 10, 54), 0])
    ws.append([730000001, 733000002, "PPWEB20260819000502", 20000002, -4.3, 15131.7405, 15131.7405, "Transaction Failed",
               dt.datetime(2026, 8, 19, 16, 42, 9), dt.datetime(2026, 8, 19, 16, 42, 10), 7])
    wb.save(f"{OUT}/telecel.xlsx")


def zimswitch():
    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = "Sheet1"
    ws.append(["Issuer", "TerminalId", "Device", "DateTime", "MessageType", "Type", "STAN", "RRN", "PAN", "RspCode", "SwitchFees",
               "AcquirerFees", "IssuerFees", "CashAmt", "CashCurr", "TranAmt", "TranCurr", "NetAmt"])
    ws.append(["Test Bank", "T0000001", "POS", dt.datetime(2026, 10, 1, 18, 36), 200, 0, 8781, 351000001, "'500000*********0001", 51,
               -0.12, -0.1, 0.22, 0, "USD", 4.2, "USD", 0.22])
    ws.append(["Test Bank", "T0000002", "POS", dt.datetime(2026, 10, 1, 18, 38), 200, 0, 83502, 627418000000, "'500000*********0002", 0,
               0, 0, 0, 0, "USD", 1, "USD", 1])
    ws.append(["Test Bank", "T0000003", "POS", dt.datetime(2026, 9, 30, 22, 13), 200, 0, 1031, 351000003, "'500000*********0003", 0,
               -0.12, -0.06, 0.18, 0, "USD", 20, "USD", 20.18])
    wb.save(f"{OUT}/zimswitch.xlsx")


def omari():
    c = canvas.Canvas(f"{OUT}/omari.pdf", pagesize=landscape(A4))
    y = 560
    def line(text, x=20):
        nonlocal y
        c.setFont("Courier", 7)
        c.drawString(x, y, text)
        y -= 10
    line("Wallet USD Statement")
    line("Name: TEST MERCHANT")
    line("Statement Period: 01-Jan-2026 to 08-Jun-2026")
    line("Posting Date Value Date Ref Transaction Description Terminal ID Debits Credits Balance")
    line("01/01/2026 01/01/2026 Statement Opening Balance 0.00")
    line("12/01/2026 12/01/2026 aaaaaaaa0001 Merchant Settlement MU000001 10.00 10.00")
    line("08:24:51 08:24:51 TEST MERCHANT *ABC123paygo.co.zw")
    line("AfZW:CustomerNumber:263770000001:Name:Test Customer")
    line("18/02/2026 18/02/2026 000000000002 Adjustment Debit OMZW9099 (110.00) 0.00")
    line("09:29:10 09:29:10 OMARI MOBILE Harare")
    line("ZW:Reference:MERCHANT LIQUIDATION TEST")
    line("19/02/2026 19/02/2026 aaaaaaaa0003 Merchant Settlement MU000001 40.00 50.00")
    line("17:20:46 17:20:46 TEST MERCHANT *XYZ789paygo.co.zw")
    line("AfZW:CustomerNumber:263770000002:Name:Another Customer")
    c.save()


for f in (ecocash, innbucks, netone, onemoney, telecel, zimswitch, omari):
    f()
print("fixtures written to", OUT)
