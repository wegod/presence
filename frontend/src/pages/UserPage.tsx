import { useCallback, useEffect, useState } from 'react';
import {
  cancelOrder,
  errMessage,
  formatCent,
  listOrders,
  login,
  logout,
  me,
  newRequestId,
  orderStatusLabel,
  payOrder,
  placeOrder,
  type Order,
} from '../api';

export default function UserPage() {
  const [userId, setUserId] = useState<number | null>(null);
  const [authReady, setAuthReady] = useState(false);
  const [activityId, setActivityId] = useState('');
  const [skuId, setSkuId] = useState('1001');
  const [orders, setOrders] = useState<Order[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [placing, setPlacing] = useState(false);
  const [authBusy, setAuthBusy] = useState(false);

  const refreshOrders = useCallback(async () => {
    if (userId == null) {
      setOrders([]);
      return;
    }
    const list = await listOrders();
    setOrders(list);
  }, [userId]);

  useEffect(() => {
    me()
      .then((data) => {
        if (data?.userId != null) {
          setUserId(data.userId);
        }
      })
      .catch(() => undefined)
      .finally(() => setAuthReady(true));
  }, []);

  useEffect(() => {
    if (userId == null) return;
    refreshOrders().catch(() => undefined);
  }, [userId, refreshOrders]);

  async function handleLogin() {
    setAuthBusy(true);
    setError(null);
    try {
      const data = await login();
      setUserId(data.userId);
      setInfo('登录成功，会话已写入 Cookie');
    } catch (e) {
      setError(errMessage(e));
    } finally {
      setAuthBusy(false);
    }
  }

  async function handleLogout() {
    setAuthBusy(true);
    setError(null);
    try {
      await logout();
      setUserId(null);
      setOrders([]);
      setInfo('已退出登录');
    } catch (e) {
      setError(errMessage(e));
    } finally {
      setAuthBusy(false);
    }
  }

  async function handleFlashSale() {
    if (userId == null) {
      setError('请先登录');
      return;
    }
    const aid = Number(activityId);
    const sid = Number(skuId);
    if (!Number.isFinite(aid) || aid <= 0) {
      setError('请填写有效的活动 ID');
      return;
    }
    if (!Number.isFinite(sid) || sid <= 0) {
      setError('请填写有效的 SKU ID');
      return;
    }

    // 每次点击生成全新 requestId，不跨次复用
    const requestId = newRequestId();
    setPlacing(true);
    setError(null);
    setInfo(null);
    try {
      const order = await placeOrder({
        activityId: aid,
        skuId: sid,
        requestId,
        qty: 1,
      });
      setInfo(`抢购成功，订单 #${order.orderId} · ${orderStatusLabel(order.status)}`);
      await refreshOrders();
    } catch (e) {
      setError(errMessage(e));
    } finally {
      setPlacing(false);
    }
  }

  async function handlePay(orderId: number) {
    setError(null);
    try {
      const pay = await payOrder(orderId);
      setInfo(`支付完成 · ${orderStatusLabel(pay.status)}`);
      await refreshOrders();
    } catch (e) {
      setError(errMessage(e));
    }
  }

  async function handleCancel(orderId: number) {
    setError(null);
    try {
      await cancelOrder(orderId);
      setInfo(`订单 #${orderId} 已取消`);
      await refreshOrders();
    } catch (e) {
      setError(errMessage(e));
    }
  }

  return (
    <div className="user-page">
      <section className="hero">
        <p className="brand">Flash Sale</p>
        <h1>限时闪购</h1>
        <p className="tagline">填写活动 ID 与 SKU ID 后一键下单。支付窗 1 分钟，请尽快完成支付。</p>
        <div className="hero-auth">
          {!authReady ? (
            <span className="muted">检查登录状态…</span>
          ) : userId != null ? (
            <>
              <span className="session">已登录 · {userId}</span>
              <button type="button" className="btn ghost" onClick={handleLogout} disabled={authBusy}>
                退出
              </button>
            </>
          ) : (
            <button type="button" className="btn primary" onClick={handleLogin} disabled={authBusy}>
              {authBusy ? '登录中…' : '登录'}
            </button>
          )}
        </div>
      </section>

      {(error || info) && (
        <div className={`banner ${error ? 'error' : 'ok'}`} role="status">
          {error ?? info}
        </div>
      )}

      <section className="buy-panel">
        <p className="hint">活动 ID 可在管理页创建/列表中查看；抢购前请确认活动已预热且在售卖时间内。</p>
        <div className="buy-fields">
          <label>
            活动 ID
            <input
              inputMode="numeric"
              value={activityId}
              onChange={(e) => setActivityId(e.target.value)}
              placeholder="必填，例如 1"
              aria-label="活动 ID"
              required
            />
          </label>
          <label>
            SKU ID
            <input
              inputMode="numeric"
              value={skuId}
              onChange={(e) => setSkuId(e.target.value)}
              placeholder="例如 1001"
              aria-label="SKU ID"
            />
          </label>
        </div>
        <button
          type="button"
          className="btn flash"
          onClick={handleFlashSale}
          disabled={placing || userId == null}
        >
          {placing ? '抢购中…' : '立即抢购'}
        </button>
        {userId == null && <p className="hint">登录后即可抢购；Cookie 刷新后仍保留。</p>}
      </section>

      <section className="orders-section">
        <div className="section-head">
          <h2>我的订单</h2>
          <button
            type="button"
            className="btn ghost"
            disabled={userId == null}
            onClick={() => refreshOrders().catch((e) => setError(errMessage(e)))}
          >
            刷新
          </button>
        </div>
        <ul className="order-list">
          {orders.map((o) => (
            <li key={o.orderId} className="order-row">
              <div className="order-main">
                <span className="order-id">#{o.orderId}</span>
                <span className={`status status-${o.status}`}>{orderStatusLabel(o.status)}</span>
                <span className="order-meta">
                  SKU {o.skuId} · {formatCent(o.amountCent)}
                </span>
              </div>
              <div className="order-actions">
                {o.status === 'PENDING_PAY' && (
                  <button type="button" className="btn primary" onClick={() => handlePay(o.orderId)}>
                    支付
                  </button>
                )}
                {(o.status === 'PENDING_PAY' || o.status === 'PROCESSING') && (
                  <button type="button" className="btn ghost" onClick={() => handleCancel(o.orderId)}>
                    取消
                  </button>
                )}
              </div>
            </li>
          ))}
          {userId != null && orders.length === 0 && <li className="hint">暂无订单</li>}
          {userId == null && <li className="hint">登录后查看订单</li>}
        </ul>
      </section>
    </div>
  );
}
