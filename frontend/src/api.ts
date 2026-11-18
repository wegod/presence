/** Unified API envelope: success when code === 0 */
export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

export class ApiError extends Error {
  readonly code: number;
  readonly data: unknown;

  constructor(code: number, message: string, data: unknown = null) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.data = data;
  }
}

export interface LoginData {
  userId: number;
}

export interface CreateActivityBody {
  name: string;
  startTime: string;
  endTime: string;
  skuId: number;
  skuName: string;
  totalStock: number;
  perUserLimit: number;
  unitPriceCent: number;
}

export interface Activity {
  activityId: number;
  name: string;
  startTime: string;
  endTime: string;
  status: number;
  skuId: number;
  skuName: string;
  totalStock: number;
  availableStock: number;
  perUserLimit: number;
  unitPriceCent: number;
  saleEnabled: boolean;
  /** Redis 剩余库存；未预热时为 null */
  redisStock?: number | null;
  /** 是否已写入 Redis（warmup_token） */
  warmed?: boolean | null;
  /** Redis 可售标记；未预热时为 null */
  redisSaleEnabled?: boolean | null;
}

export interface PageResult<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

export interface StockInfo {
  activityId: number;
  skuId: number;
  skuName: string;
  totalStock: number;
  availableStock: number;
  perUserLimit: number;
  saleEnabled: boolean;
  unitPriceCent: number;
  redisStock?: number | null;
  warmed?: boolean | null;
  redisSaleEnabled?: boolean | null;
}

export interface Order {
  orderId: number;
  userId: number;
  requestId: string;
  activityId: number;
  skuId: number;
  qty: number;
  amountCent: number;
  status: string;
  payDeadline: string | null;
  createdAt: string;
}

export interface PayResult {
  orderId: number;
  payTradeNo: string;
  status: string;
  amountCent: number;
}

export interface PlaceOrderBody {
  activityId: number;
  skuId: number;
  requestId: string;
  qty: number;
}

async function request<T>(path: string, init?: RequestInit & { credentials?: RequestCredentials }): Promise<T> {
  const { credentials = 'include', headers, ...rest } = init ?? {};
  const res = await fetch(path, {
    credentials,
    headers: {
      'Content-Type': 'application/json',
      ...(headers ?? {}),
    },
    ...rest,
  });

  let body: ApiResponse<T>;
  try {
    body = (await res.json()) as ApiResponse<T>;
  } catch {
    throw new ApiError(-1, `HTTP ${res.status}: 响应无法解析`);
  }

  if (body.code !== 0) {
    throw new ApiError(body.code, body.message || `业务错误`, body.data);
  }

  return body.data;
}

export function login(): Promise<LoginData> {
  return request<LoginData>('/api/auth/login', { method: 'POST', body: '{}' });
}

export function logout(): Promise<null> {
  return request<null>('/api/auth/logout', { method: 'POST', body: '{}' });
}

/** 从 Cookie 恢复登录态；未登录返回 null */
export function me(): Promise<LoginData | null> {
  return request<LoginData | null>('/api/auth/me');
}

export function createActivity(body: CreateActivityBody): Promise<Activity> {
  return request<Activity>('/api/admin/activities', {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function listActivities(page = 1, size = 20): Promise<PageResult<Activity>> {
  return request<PageResult<Activity>>(`/api/admin/activities?page=${page}&size=${size}`);
}

export function getActivity(id: number): Promise<Activity> {
  return request<Activity>(`/api/admin/activities/${id}`);
}

export function getStock(id: number): Promise<StockInfo> {
  return request<StockInfo>(`/api/admin/activities/${id}/stock`);
}

export function deleteActivity(id: number): Promise<null> {
  return request<null>(`/api/admin/activities/${id}`, { method: 'DELETE' });
}

export function warmupActivity(id: number): Promise<number> {
  return request<number>(`/api/admin/activities/${id}/warmup`, { method: 'POST' });
}

/** 管理端：按活动查询抢购成功写入 MySQL 的订单 */
export function listActivityOrders(
  id: number,
  page = 1,
  size = 50,
): Promise<PageResult<Order>> {
  return request<PageResult<Order>>(`/api/admin/activities/${id}/orders?page=${page}&size=${size}`);
}

export function placeOrder(body: PlaceOrderBody): Promise<Order> {
  return request<Order>('/api/seckill/orders', {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

/** 压测用：省略 Cookie，用 X-User-Id 模拟多用户（后端优先 Cookie） */
export function placeOrderAsUser(userId: number, body: PlaceOrderBody): Promise<Order> {
  return request<Order>('/api/seckill/orders', {
    method: 'POST',
    credentials: 'omit',
    headers: { 'X-User-Id': String(userId) },
    body: JSON.stringify(body),
  });
}

export function listOrders(): Promise<Order[]> {
  return request<Order[]>('/api/seckill/orders');
}

export function getOrder(id: number): Promise<Order> {
  return request<Order>(`/api/seckill/orders/${id}`);
}

export function payOrder(id: number): Promise<PayResult> {
  return request<PayResult>(`/api/seckill/orders/${id}/pay`, { method: 'POST' });
}

export function cancelOrder(id: number): Promise<Order> {
  return request<Order>(`/api/seckill/orders/${id}/cancel`, { method: 'POST' });
}

/** datetime-local 控件值：YYYY-MM-DDTHH:mm */
export function toDatetimeLocalValue(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` +
    `T${pad(d.getHours())}:${pad(d.getMinutes())}`
  );
}

/** API LocalDateTime：补全秒 */
export function datetimeLocalToApi(value: string): string {
  if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value)) {
    return `${value}:00`;
  }
  return value;
}

export function formatCent(cent: number): string {
  return `¥${(cent / 100).toFixed(2)}`;
}

export function newRequestId(): string {
  return crypto.randomUUID();
}

const ORDER_STATUS_LABELS: Record<string, string> = {
  PROCESSING: '处理中',
  PENDING_PAY: '待支付',
  PAID: '已支付',
  CANCELED_MANUAL: '已取消',
  CANCELED_TIMEOUT: '超时取消',
  CANCELED_SYSTEM: '系统取消',
  PAY_FAILED: '支付失败',
};

export function orderStatusLabel(status: string): string {
  return ORDER_STATUS_LABELS[status] ?? status;
}

export function errMessage(err: unknown): string {
  if (err instanceof ApiError) {
    return err.message || '操作失败';
  }
  return String(err);
}
