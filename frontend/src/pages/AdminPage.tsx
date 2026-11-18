import { FormEvent, useCallback, useEffect, useRef, useState } from 'react';
import {
  Activity,
  createActivity,
  datetimeLocalToApi,
  deleteActivity,
  errMessage,
  formatCent,
  getActivity,
  listActivities,
  listActivityOrders,
  newRequestId,
  Order,
  orderStatusLabel,
  placeOrderAsUser,
  toDatetimeLocalValue,
  warmupActivity,
} from '../api';

type LogLevel = 'info' | 'ok' | 'err';

interface LogLine {
  id: number;
  t: string;
  level: LogLevel;
  text: string;
}

const PAGE_SIZE = 10;

function nowStamp(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

function redisStockLabel(a: Activity): string {
  if (a.warmed) {
    return a.redisStock != null ? String(a.redisStock) : '—';
  }
  return '未预热';
}

function redisSaleLabel(a: Activity): string {
  if (!a.warmed) return '—';
  if (a.redisSaleEnabled == null) return '—';
  return a.redisSaleEnabled ? '是' : '否';
}

export default function AdminPage() {
  const [activities, setActivities] = useState<Activity[]>([]);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [selected, setSelected] = useState<Activity | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [opsBusy, setOpsBusy] = useState(false);
  const [logs, setLogs] = useState<LogLine[]>([]);
  const logSeq = useRef(0);
  const logBoxRef = useRef<HTMLDivElement>(null);

  const [form, setForm] = useState(() => {
    const start = new Date(Date.now() - 60_000);
    const end = new Date(Date.now() + 3_600_000);
    return {
      name: 'Flash Sale Demo',
      startTime: toDatetimeLocalValue(start),
      endTime: toDatetimeLocalValue(end),
      skuId: 1001,
      skuName: 'Presence Tee',
      totalStock: 100,
      perUserLimit: 1,
      unitPriceCent: 9900,
    };
  });

  const [burst, setBurst] = useState({ activityId: '', concurrency: 20, purchasesPerUser: 1 });
  const [orders, setOrders] = useState<Order[]>([]);
  const [ordersTotal, setOrdersTotal] = useState(0);
  const [ordersVisible, setOrdersVisible] = useState(false);
  const [ordersBusy, setOrdersBusy] = useState(false);

  const appendLog = useCallback((level: LogLevel, text: string) => {
    logSeq.current += 1;
    setLogs((prev) => [...prev, { id: logSeq.current, t: nowStamp(), level, text }]);
  }, []);

  useEffect(() => {
    const el = logBoxRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [logs]);

  const refreshList = useCallback(async (targetPage = page) => {
    const result = await listActivities(targetPage, PAGE_SIZE);
    setActivities(result.items);
    setTotal(result.total);
    setPage(result.page);
    return result;
  }, [page]);

  useEffect(() => {
    refreshList(page).catch((e) => setError(errMessage(e)));
  }, [page]); // eslint-disable-line react-hooks/exhaustive-deps -- only reload when page changes

  async function handleCreate(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const created = await createActivity({
        ...form,
        startTime: datetimeLocalToApi(form.startTime),
        endTime: datetimeLocalToApi(form.endTime),
        skuId: Number(form.skuId),
        totalStock: Number(form.totalStock),
        perUserLimit: Number(form.perUserLimit),
        unitPriceCent: Number(form.unitPriceCent),
      });
      setSelected(created);
      setInfo(`活动已创建 #${created.activityId}`);
      setPage(1);
      await refreshList(1);
    } catch (err) {
      setError(errMessage(err));
    } finally {
      setBusy(false);
    }
  }

  async function handleSelect(id: number) {
    setBusy(true);
    setError(null);
    setOrdersVisible(false);
    setOrders([]);
    setOrdersTotal(0);
    try {
      const detail = await getActivity(id);
      setSelected(detail);
      setInfo(
        `已加载活动 #${id} · MySQL ${detail.availableStock}/${detail.totalStock}` +
          (detail.warmed ? ` · Redis ${detail.redisStock ?? '—'}` : ' · 未预热'),
      );
    } catch (err) {
      setError(errMessage(err));
    } finally {
      setBusy(false);
    }
  }

  async function handleViewOrders() {
    if (!selected) return;
    setOrdersBusy(true);
    setError(null);
    try {
      const result = await listActivityOrders(selected.activityId, 1, 50);
      setOrders(result.items);
      setOrdersTotal(result.total);
      setOrdersVisible(true);
      setInfo(`活动 #${selected.activityId} 订单 ${result.total} 条（本页 ${result.items.length}）`);
    } catch (err) {
      setError(errMessage(err));
    } finally {
      setOrdersBusy(false);
    }
  }

  async function handleRefreshDetail() {
    if (!selected) return;
    setBusy(true);
    setError(null);
    try {
      const detail = await getActivity(selected.activityId);
      setSelected(detail);
      setInfo(
        `详情已刷新 #${detail.activityId} · MySQL ${detail.availableStock}/${detail.totalStock}` +
          (detail.warmed ? ` · Redis ${detail.redisStock ?? '—'}` : ' · 未预热'),
      );
    } catch (err) {
      setError(errMessage(err));
    } finally {
      setBusy(false);
    }
  }

  async function handleWarmup() {
    if (!selected || selected.warmed) return;
    setBusy(true);
    setError(null);
    try {
      const n = await warmupActivity(selected.activityId);
      const detail = await getActivity(selected.activityId);
      setSelected(detail);
      setInfo(`预热完成（写入 ${n} 个 SKU），Redis 库存 ${detail.redisStock ?? '—'}`);
      await refreshList(page);
    } catch (err) {
      setError(errMessage(err));
    } finally {
      setBusy(false);
    }
  }

  async function handleDelete(id: number) {
    const ok = window.confirm(
      `确认删除活动 #${id}？\n进行中的活动会被拒绝；其它活动将软删除（下架）。`,
    );
    if (!ok) return;
    setBusy(true);
    setError(null);
    try {
      await deleteActivity(id);
      if (selected?.activityId === id) {
        setSelected(null);
        setOrdersVisible(false);
        setOrders([]);
        setOrdersTotal(0);
      }
      setInfo(`活动 #${id} 已删除`);
      const result = await refreshList(page);
      if (result.items.length === 0 && result.page > 1) {
        setPage(result.page - 1);
      }
    } catch (err) {
      setError(errMessage(err));
    } finally {
      setBusy(false);
    }
  }

  /** 运维面板：指定活动 → 预热(如需) → 并发数×每人购买次数 模拟抢单 */
  async function runSimulateGrab() {
    const targetId = Number(burst.activityId);
    if (!Number.isFinite(targetId) || targetId <= 0) {
      setError('请填写活动 ID');
      appendLog('err', '活动 ID 必填');
      return;
    }

    setOpsBusy(true);
    setError(null);
    setLogs([]);
    const users = Math.max(1, Math.min(Number(burst.concurrency) || 1, 200));
    const perUser = Math.max(1, Math.min(Number(burst.purchasesPerUser) || 1, 50));
    const totalAttempts = users * perUser;
    appendLog(
      'info',
      `开始模拟抢单：活动 #${targetId} · 并发用户 ${users} × 每人 ${perUser} 次 = ${totalAttempts} 次下单`,
    );
    try {
      let target = await getActivity(targetId);
      appendLog(
        'ok',
        `已加载活动 #${target.activityId} SKU=${target.skuId} · Redis ${redisStockLabel(target)}`,
      );
      setSelected(target);
      setBurst((b) => ({ ...b, activityId: String(target.activityId) }));

      if (!target.warmed) {
        appendLog('info', '执行预热…');
        const warmed = await warmupActivity(target.activityId);
        appendLog('ok', `预热完成 warmed=${warmed}`);
        target = await getActivity(target.activityId);
        setSelected(target);
        appendLog('info', `预热后 Redis 库存 ${redisStockLabel(target)}`);
      } else {
        appendLog('info', `已预热，跳过（Redis 库存 ${target.redisStock ?? '—'}）`);
      }

      appendLog(
        'info',
        `并发下单 ${totalAttempts} 次（${users} 个用户，每人 ${perUser} 次；X-User-Id + 新 requestId）…`,
      );
      const tasks: Array<{ uid: number; attempt: number }> = [];
      for (let u = 0; u < users; u += 1) {
        for (let p = 0; p < perUser; p += 1) {
          tasks.push({ uid: 8_000_000_000 + u, attempt: p + 1 });
        }
      }

      const results = await Promise.allSettled(
        tasks.map(({ uid }) =>
          placeOrderAsUser(uid, {
            activityId: target.activityId,
            skuId: target.skuId,
            requestId: newRequestId(),
            qty: 1,
          }),
        ),
      );

      let ok = 0;
      let fail = 0;
      for (let i = 0; i < results.length; i += 1) {
        const r = results[i];
        const { uid, attempt } = tasks[i];
        if (r.status === 'fulfilled') {
          ok += 1;
          appendLog(
            'ok',
            `用户 ${uid} 第 ${attempt} 次成功 orderId=${r.value.orderId}`,
          );
        } else {
          fail += 1;
          const msg = errMessage(r.reason);
          appendLog('err', `用户 ${uid} 第 ${attempt} 次失败：${msg}`);
        }
      }
      appendLog(ok > 0 ? 'ok' : 'err', `下单结果：成功 ${ok} / 失败 ${fail}（共 ${totalAttempts}）`);

      const detail = await getActivity(target.activityId);
      setSelected(detail);
      appendLog(
        'info',
        `剩余库存 · MySQL ${detail.availableStock}/${detail.totalStock} · Redis ${redisStockLabel(detail)}`,
      );
      setInfo(
        `模拟抢单结束：成功 ${ok}，失败 ${fail}，Redis 剩余 ${redisStockLabel(detail)}`,
      );
      await refreshList(page);
    } catch (err) {
      appendLog('err', errMessage(err));
      setError(errMessage(err));
    } finally {
      setOpsBusy(false);
    }
  }

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const warmed = Boolean(selected?.warmed);

  return (
    <div className="admin-page">
      <header className="admin-hero">
        <div>
          <p className="brand sm">Flash Sale</p>
          <h1>活动管理</h1>
          <p className="tagline">创建 / 列表 / 库存 / 预热，以及可复现的运维压测面板</p>
        </div>
      </header>

      {(error || info) && (
        <div className={`banner ${error ? 'error' : 'ok'}`} role="status">
          {error ?? info}
        </div>
      )}

      <div className="admin-grid">
        <section className="panel admin-detail">
          <div className="section-head">
            <h2>活动详情</h2>
            {selected && (
              <button type="button" className="btn ghost" onClick={handleRefreshDetail} disabled={busy}>
                刷新
              </button>
            )}
          </div>
          {!selected ? (
            <p className="hint">从列表选择或创建新活动</p>
          ) : (
            <>
              <dl className="detail-dl">
                <div>
                  <dt>ID</dt>
                  <dd>{selected.activityId}</dd>
                </div>
                <div>
                  <dt>名称</dt>
                  <dd>{selected.name}</dd>
                </div>
                <div>
                  <dt>SKU</dt>
                  <dd>
                    {selected.skuId} · {selected.skuName}
                  </dd>
                </div>
                <div>
                  <dt>MySQL 库存</dt>
                  <dd>
                    {selected.availableStock} / {selected.totalStock}
                  </dd>
                </div>
                <div>
                  <dt>Redis 库存</dt>
                  <dd>{redisStockLabel(selected)}</dd>
                </div>
                <div>
                  <dt>预热状态</dt>
                  <dd>{warmed ? '已预热' : '未预热'}</dd>
                </div>
                <div>
                  <dt>Redis 可售</dt>
                  <dd>{redisSaleLabel(selected)}</dd>
                </div>
                <div>
                  <dt>限购</dt>
                  <dd>{selected.perUserLimit}</dd>
                </div>
                <div>
                  <dt>可售 (MySQL)</dt>
                  <dd>{selected.saleEnabled ? '是' : '否'}</dd>
                </div>
                <div>
                  <dt>时间</dt>
                  <dd className="wrap">
                    {selected.startTime} → {selected.endTime}
                  </dd>
                </div>
              </dl>
              <div className="form-actions">
                <button
                  type="button"
                  className="btn primary"
                  onClick={handleWarmup}
                  disabled={busy || warmed}
                  title={warmed ? '已在 Redis 中，无需重复预热' : undefined}
                >
                  {warmed ? '已预热' : '手动预热'}
                </button>
                <button
                  type="button"
                  className="btn ghost"
                  onClick={handleViewOrders}
                  disabled={busy || ordersBusy}
                >
                  {ordersBusy ? '加载中…' : '查看订单'}
                </button>
                <button
                  type="button"
                  className="btn ghost"
                  onClick={() => handleDelete(selected.activityId)}
                  disabled={busy}
                >
                  删除
                </button>
              </div>
              {ordersVisible && (
                <div className="admin-orders">
                  <div className="section-head">
                    <h3>抢购订单</h3>
                    <span className="muted">共 {ordersTotal} 条</span>
                  </div>
                  {orders.length === 0 ? (
                    <p className="hint">暂无订单</p>
                  ) : (
                    <div className="admin-orders-scroll">
                      <table className="admin-orders-table">
                        <thead>
                          <tr>
                            <th>订单号</th>
                            <th>用户ID</th>
                            <th>状态</th>
                            <th>SKU</th>
                            <th>数量</th>
                            <th>金额</th>
                            <th>支付截止</th>
                            <th>创建时间</th>
                          </tr>
                        </thead>
                        <tbody>
                          {orders.map((o) => (
                            <tr key={o.orderId}>
                              <td>{o.orderId}</td>
                              <td>{o.userId}</td>
                              <td>
                                <span className={`status status-${o.status}`}>
                                  {orderStatusLabel(o.status)}
                                </span>
                              </td>
                              <td>{o.skuId}</td>
                              <td>{o.qty}</td>
                              <td>{formatCent(o.amountCent)}</td>
                              <td className="wrap">{o.payDeadline ?? '—'}</td>
                              <td className="wrap">{o.createdAt}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </div>
              )}
            </>
          )}
        </section>

        <section className="panel admin-list">
          <div className="section-head">
            <h2>活动列表</h2>
            <button
              type="button"
              className="btn ghost"
              onClick={() => refreshList(page).catch((e) => setError(errMessage(e)))}
            >
              刷新
            </button>
          </div>
          <ul className="activity-list">
            {activities.map((a) => (
              <li key={a.activityId} className="activity-row">
                <button
                  type="button"
                  className={`activity-item ${selected?.activityId === a.activityId ? 'active' : ''}`}
                  onClick={() => handleSelect(a.activityId)}
                >
                  <span className="activity-title">
                    #{a.activityId} {a.name}
                  </span>
                  <span className="muted">
                    SKU {a.skuId} · MySQL {a.availableStock}/{a.totalStock}
                    {a.warmed ? ` · Redis ${a.redisStock ?? '—'}` : ' · 未预热'} · {formatCent(a.unitPriceCent)}
                  </span>
                </button>
                <button
                  type="button"
                  className="btn ghost danger-btn"
                  disabled={busy}
                  onClick={() => handleDelete(a.activityId)}
                  title="删除活动"
                >
                  删除
                </button>
              </li>
            ))}
            {activities.length === 0 && <li className="hint">暂无活动</li>}
          </ul>
          <div className="pager">
            <button
              type="button"
              className="btn ghost"
              disabled={page <= 1 || busy}
              onClick={() => setPage((p) => Math.max(1, p - 1))}
            >
              上一页
            </button>
            <span className="muted">
              {page} / {totalPages} · 共 {total} 条
            </span>
            <button
              type="button"
              className="btn ghost"
              disabled={page >= totalPages || busy}
              onClick={() => setPage((p) => p + 1)}
            >
              下一页
            </button>
          </div>
        </section>

        <section className="panel admin-create">
          <h2>创建活动</h2>
          <p className="hint">开始时间可设为过去以便立即开抢；创建后请手动预热或等待 Job。</p>
          <form className="form-grid" onSubmit={handleCreate}>
            <label>
              名称
              <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
            </label>
            <label>
              开始
              <input
                type="datetime-local"
                value={form.startTime}
                onChange={(e) => setForm({ ...form, startTime: e.target.value })}
              />
            </label>
            <label>
              结束
              <input
                type="datetime-local"
                value={form.endTime}
                onChange={(e) => setForm({ ...form, endTime: e.target.value })}
              />
            </label>
            <label>
              SKU ID
              <input
                type="number"
                value={form.skuId}
                onChange={(e) => setForm({ ...form, skuId: Number(e.target.value) })}
              />
            </label>
            <label>
              商品名
              <input value={form.skuName} onChange={(e) => setForm({ ...form, skuName: e.target.value })} />
            </label>
            <label>
              库存
              <input
                type="number"
                value={form.totalStock}
                onChange={(e) => setForm({ ...form, totalStock: Number(e.target.value) })}
              />
            </label>
            <label>
              限购
              <input
                type="number"
                value={form.perUserLimit}
                onChange={(e) => setForm({ ...form, perUserLimit: Number(e.target.value) })}
              />
            </label>
            <label>
              单价（分）
              <input
                type="number"
                value={form.unitPriceCent}
                onChange={(e) => setForm({ ...form, unitPriceCent: Number(e.target.value) })}
              />
            </label>
            <div className="form-actions">
              <button type="submit" className="btn primary" disabled={busy}>
                创建
              </button>
            </div>
          </form>
        </section>

        <section className="panel ops-panel">
          <h2>运维压测面板</h2>
          <p className="hint">
            填写活动 ID，按「并发数 × 每人购买次数」对该活动库存发起模拟抢单。浏览器内调用 API，日志见下方。
          </p>
          <div className="ops-controls">
            <label>
              活动 ID
              <input
                inputMode="numeric"
                placeholder="必填"
                required
                value={burst.activityId}
                onChange={(e) => setBurst({ ...burst, activityId: e.target.value })}
              />
            </label>
            <label>
              并发数
              <input
                type="number"
                min={1}
                max={200}
                value={burst.concurrency}
                onChange={(e) => setBurst({ ...burst, concurrency: Number(e.target.value) })}
              />
            </label>
            <label>
              每人购买次数
              <input
                type="number"
                min={1}
                max={50}
                value={burst.purchasesPerUser}
                onChange={(e) =>
                  setBurst({ ...burst, purchasesPerUser: Number(e.target.value) })
                }
              />
            </label>
          </div>
          <div className="form-actions">
            <button type="button" className="btn accent" onClick={runSimulateGrab} disabled={opsBusy}>
              {opsBusy ? '执行中…' : '模拟抢单'}
            </button>
          </div>
          <div className="ops-log" ref={logBoxRef}>
            {logs.length === 0 ? (
              <p className="hint">尚无执行记录</p>
            ) : (
              logs.map((line) => (
                <div key={line.id} className={`log-line ${line.level}`}>
                  <span className="log-t">{line.t}</span>
                  <span>{line.text}</span>
                </div>
              ))
            )}
          </div>
        </section>
      </div>
    </div>
  );
}
