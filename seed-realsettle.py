# -*- coding: utf-8 -*-
# 真结算演示基线复位脚本（可重复运行）：
#   1) 清空 account_db 的 holding_change_log / fund_transaction_log（幂等日志，防 CT 重启后 tradeNo 撞键被去重）
#   2) 资金账户余额复位基线（2026* → 1000万，其它(如FA) → 100万；冻结清零）
#   3) 持仓表清空并按 [每证券账户 × 每只股票 = 100000 股] 播种（avg_cost 取昨收）
# 用法：/d/python3.11.8/python seed-realsettle.py
import pymysql

SEED_QTY = 100000

ct = pymysql.connect(host="localhost", user="root", password="root", database="central_trading", charset="utf8mb4")
ac = pymysql.connect(host="localhost", user="root", password="root", database="account_db", charset="utf8mb4")
try:
    with ct.cursor() as c:
        c.execute("SELECT stock_code, stock_name, previous_close FROM stock_info ORDER BY stock_code")
        stocks = c.fetchall()  # [(code, name, prev_close), ...]

    with ac.cursor() as a:
        a.execute("SELECT sec_acc_no FROM security_account")
        sec_accs = [r[0] for r in a.fetchall()]
        a.execute("SELECT fund_acc_no FROM fund_account")
        fund_accs = [r[0] for r in a.fetchall()]

        # 1) 清幂等日志
        a.execute("TRUNCATE TABLE holding_change_log")
        a.execute("TRUNCATE TABLE fund_transaction_log")

        # 2) 资金基线复位
        for f in fund_accs:
            base = 10000000 if str(f).startswith("2026") else 1000000
            a.execute("UPDATE fund_account SET available_balance=%s, frozen_balance=0 WHERE fund_acc_no=%s", (base, f))

        # 3) 持仓清空 + 播种
        a.execute("TRUNCATE TABLE holding")
        n = 0
        for s in sec_accs:
            for (code, name, prev) in stocks:
                a.execute(
                    "INSERT INTO holding (sec_acc_no, stock_code, stock_name, quantity, frozen_quantity, avg_cost) "
                    "VALUES (%s,%s,%s,%s,0,%s)",
                    (s, code, name, SEED_QTY, prev),
                )
                n += 1
    ac.commit()

    # 校验
    with ac.cursor() as a:
        a.execute("SELECT COUNT(*), MIN(quantity), MAX(quantity) FROM holding")
        hc = a.fetchone()
        a.execute("SELECT COUNT(*) FROM holding_change_log")
        lc = a.fetchone()[0]
    print(f"OK: 证券账户 {len(sec_accs)} 个 × 股票 {len(stocks)} 只 → holding {hc[0]} 行 (qty {hc[1]}~{hc[2]}); 资金账户 {len(fund_accs)} 个已复位; 幂等日志已清(holding_change_log={lc})")
finally:
    ct.close(); ac.close()
