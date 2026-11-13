/**
 * Cohabit & Roster Encrypted API Bridge Service
 * 
 * Provides end-to-end integration for:
 * 1. Master Encrypted Bridge Sync (AES-256-GCM scoped)
 * 2. Shopping List & Leftover Food Inventory
 * 3. Work Shifts & Meal Prep Schedule
 * 4. Shared Grocery & Food Expenses (50/50 Roommate Ledger)
 * 5. Private Personal Finance Vault (Strictly Scoped)
 */

export type AuthenticatedOwner = "Ozric" | "Pawan";
export type EncryptionScope = "USER_OZRIC" | "USER_PAWAN";

export type ShoppingCategory =
  | "Produce & Groceries"
  | "Dairy & Eggs"
  | "Meat & Poultry"
  | "Snacks & Drinks"
  | "Household & Cleaning"
  | "Other";

export type ShoppingPriority = "Urgent (Running Low)" | "Normal" | "Low";

export interface ShoppingListItem {
  id: string;
  title: string;
  quantity: string;
  category: ShoppingCategory;
  priority: ShoppingPriority;
  addedBy: string;
  completed: boolean;
  notes: string;
  estimatedCost: number | null;
  createdAt: string | number;
}

export interface CreateShoppingItemPayload {
  title: string;
  quantity: string;
  category: ShoppingCategory;
  priority: ShoppingPriority;
  notes?: string;
  estimatedCost?: number | null;
}

export interface WorkShift {
  id: string | number;
  user: string;
  date: string; // YYYY-MM-DD
  startTime: string;
  endTime: string;
  title: string;
  employerName: string;
  netHours: number;
  isSickLeave: boolean;
  isDayOff: boolean;
  completed: boolean;
  notes: string;
}

export interface SharedExpense {
  id: string;
  title: string;
  amount: number;
  paidBy: string;
  splitType: "50/50" | string;
  category: string;
  notes: string;
  timestamp?: number | string;
}

export interface ExpenseLedgerSummary {
  debtSummary: string;
  netDifference: number;
}

export interface ExpensesResponse {
  expenses: SharedExpense[];
  ledger: ExpenseLedgerSummary;
}

export interface CreateExpensePayload {
  title: string;
  amount: number;
  splitType?: "50/50";
  category?: string;
  notes?: string;
}

export interface PersonalFinanceSummary {
  totalBalanceEur: number;
  accounts: Array<{
    id: string;
    name: string;
    type: string;
    balanceEur: number;
  }>;
  transactions: Array<{
    id: string;
    description: string;
    amountEur: number;
    date: string;
    category?: string;
  }>;
  savingsGoals?: Array<{
    id: string;
    title: string;
    currentEur: number;
    targetEur: number;
  }>;
  recurringBills?: Array<{
    id: string;
    title: string;
    amountEur: number;
    dueDate: string;
  }>;
}

export interface MasterBridgeSyncResponse {
  success: boolean;
  authenticatedOwner: AuthenticatedOwner;
  encryptionScope: EncryptionScope;
  data: {
    shifts: WorkShift[];
    shoppingList: ShoppingListItem[];
    expenses: SharedExpense[];
    personalFinance: {
      accounts: any[];
      transactions: any[];
      summary?: {
        totalBalanceEur: number;
      };
    };
  };
}

export class CohabitBridgeApiError extends Error {
  constructor(
    public status: number,
    public statusText: string,
    message: string,
    public body?: any
  ) {
    super(`Cohabit API Error [${status} ${statusText}]: ${message}`);
    this.name = "CohabitBridgeApiError";
  }
}

export interface CohabitConfig {
  baseUrl?: string;
  apiKey?: string;
}

export const DEFAULT_COHABIT_BASE_URL =
  "https://ais-pre-fakno4cgzgk52rt56zapwg-628972999672.asia-southeast1.run.app";

export const DEFAULT_KEYS = {
  OZRIC: "leaftover-ozric-key-2026",
  PAWAN: "leaftover-pawan-key-2026",
} as const;

export class CohabitBridgeService {
  private baseUrl: string;
  private apiKey: string;

  constructor(config?: CohabitConfig) {
    this.baseUrl = config?.baseUrl || DEFAULT_COHABIT_BASE_URL;
    this.apiKey = config?.apiKey || DEFAULT_KEYS.OZRIC;
  }

  public setApiKey(apiKey: string): void {
    this.apiKey = apiKey.trim();
  }

  public getApiKey(): string {
    return this.apiKey;
  }

  public setBaseUrl(baseUrl: string): void {
    this.baseUrl = baseUrl.trim().replace(/\/+$/, "");
  }

  public getBaseUrl(): string {
    return this.baseUrl;
  }

  public switchUser(user: "Ozric" | "Pawan"): void {
    this.apiKey = user === "Ozric" ? DEFAULT_KEYS.OZRIC : DEFAULT_KEYS.PAWAN;
  }

  private getHeaders(): Record<string, string> {
    return {
      "Content-Type": "application/json",
      "x-api-key": this.apiKey,
      Authorization: `Bearer ${this.apiKey}`,
    };
  }

  private async request<T>(
    endpoint: string,
    options: RequestInit = {}
  ): Promise<T> {
    const cleanEndpoint = endpoint.startsWith("/") ? endpoint : `/${endpoint}`;
    const url = `${this.baseUrl}${cleanEndpoint}`;

    const headers = {
      ...this.getHeaders(),
      ...(options.headers as Record<string, string> | undefined),
    };

    let response: Response;
    try {
      response = await fetch(url, {
        ...options,
        headers,
      });
    } catch (err: any) {
      throw new CohabitBridgeApiError(
        0,
        "NetworkError",
        `Failed to reach Cohabit Bridge API at ${url}: ${err?.message || err}`
      );
    }

    if (!response.ok) {
      let errorBody: any = null;
      try {
        errorBody = await response.json();
      } catch {
        try {
          errorBody = await response.text();
        } catch {
          errorBody = null;
        }
      }

      if (response.status === 401) {
        throw new CohabitBridgeApiError(
          401,
          "Unauthorized",
          "Invalid API key or expired Bridge Token. Please check credentials.",
          errorBody
        );
      }

      if (response.status === 403) {
        throw new CohabitBridgeApiError(
          403,
          "Forbidden",
          "Access denied. Cross-user personal finance or scope boundary violation.",
          errorBody
        );
      }

      throw new CohabitBridgeApiError(
        response.status,
        response.statusText,
        typeof errorBody === "object" && errorBody?.message
          ? errorBody.message
          : `Request to ${endpoint} failed with HTTP ${response.status}`,
        errorBody
      );
    }

    return response.json() as Promise<T>;
  }

  /**
   * 1. Master Encrypted Bridge Sync
   * Returns all decrypted data for the authenticated user in one call
   */
  public async getMasterSync(): Promise<MasterBridgeSyncResponse> {
    return this.request<MasterBridgeSyncResponse>("/api/v1/bridge/sync", {
      method: "GET",
    });
  }

  /**
   * 2. Shopping List & Leftover Food Inventory
   */
  public async getShoppingList(completed: boolean = false): Promise<ShoppingListItem[]> {
    const data = await this.request<any>(
      `/api/v1/shopping-list?completed=${completed}`,
      { method: "GET" }
    );
    return Array.isArray(data) ? data : data?.items || [];
  }

  public async addShoppingItem(
    payload: CreateShoppingItemPayload
  ): Promise<ShoppingListItem> {
    return this.request<ShoppingListItem>("/api/v1/shopping-list", {
      method: "POST",
      body: JSON.stringify({
        title: payload.title,
        quantity: payload.quantity || "1 item",
        category: payload.category || "Produce & Groceries",
        priority: payload.priority || "Normal",
        notes: payload.notes || "",
        estimatedCost: payload.estimatedCost ?? null,
      }),
    });
  }

  public async updateShoppingItem(
    id: string,
    updates: Partial<ShoppingListItem>
  ): Promise<ShoppingListItem> {
    return this.request<ShoppingListItem>(`/api/v1/shopping-list/${encodeURIComponent(id)}`, {
      method: "PATCH",
      body: JSON.stringify(updates),
    });
  }

  public async markItemCompleted(id: string, completed: boolean = true): Promise<ShoppingListItem> {
    return this.updateShoppingItem(id, { completed });
  }

  public async deleteShoppingItem(id: string): Promise<boolean> {
    await this.request<any>(`/api/v1/shopping-list/${encodeURIComponent(id)}`, {
      method: "DELETE",
    });
    return true;
  }

  /**
   * 3. Work Shifts & Meal Prep Schedule
   */
  public async getShifts(): Promise<WorkShift[]> {
    const data = await this.request<any>("/api/v1/shifts", {
      method: "GET",
    });
    return Array.isArray(data) ? data : data?.shifts || [];
  }

  /**
   * 4. Shared Grocery & Food Expenses (50/50 Roommate Ledger)
   */
  public async getExpenses(): Promise<ExpensesResponse> {
    const data = await this.request<any>("/api/v1/expenses", {
      method: "GET",
    });
    if (data?.expenses && data?.ledger) {
      return data as ExpensesResponse;
    }
    return {
      expenses: Array.isArray(data) ? data : data?.expenses || [],
      ledger: data?.ledger || {
        debtSummary: "Balanced",
        netDifference: 0,
      },
    };
  }

  public async logExpense(
    payload: CreateExpensePayload
  ): Promise<SharedExpense> {
    return this.request<SharedExpense>("/api/v1/expenses", {
      method: "POST",
      body: JSON.stringify({
        title: payload.title,
        amount: Number(payload.amount),
        splitType: payload.splitType || "50/50",
        category: payload.category || "Shared Groceries",
        notes: payload.notes || "",
      }),
    });
  }

  /**
   * 5. Private Personal Finance Vault (Strictly Scoped)
   */
  public async getPersonalFinance(): Promise<PersonalFinanceSummary> {
    return this.request<PersonalFinanceSummary>("/api/v1/personal-finance", {
      method: "GET",
    });
  }
}

export const cohabitBridge = new CohabitBridgeService();

