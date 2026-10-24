// Настоящие графы и находки, снятые с ядра (MethodFlowBuilder, PR #5) и анализатора роли 2 на примерах:
//   flow   — Flow.java: if/else-if, switch без default, while(true)+break/continue, try/catch/finally, метки;
//   orders — OrdersMonolith.java из тестов анализа: циклы, находки O(n²), пустой catch.
// Открываются в режиме моков: npm run dev:mock → http://localhost:5173/?demo=flow (или ?demo=orders).
import type { Diagnostics, Graph } from '../api/types';
import flow from './real/Flow.json';
import orders from './real/OrdersMonolith.json';
import mainOps from './real/MainOps.json';

export interface RealDemo {
  file: string;
  source: string;
  overview: Graph;
  /** id узла метода из overview → блок-схема. */
  methods: Record<string, Graph>;
  diagnostics: Omit<Diagnostics, 'version'>;
}

/** Снимок проекта на настоящем ядре: исходник, обзор и блок-схема processOrders. */
export interface Snapshot {
  source: string;
  overview: Graph;
  flow: Graph;
  /** Настоящая диагностика анализатора для этого состояния кода. */
  diagnostics: Omit<Diagnostics, 'version'>;
}

/**
 * ?demo=main — Main.java и операции графа, выполненные настоящим DefaultGraphOperations (main, PR #7):
 * изменить условие, вставить переменную/условие, удалить вызов, изменить заголовок цикла.
 * Режим моков отвечает «после» только на эти операции из исходного состояния.
 */
export interface OpsDemo extends Snapshot {
  file: string;
  ops: Record<string, { request: { op: string; nodeId: string; code: string | null; kind: string | null }; after: Snapshot }>;
}

export const opsDemo = mainOps as unknown as OpsDemo;

export const realDemos: Record<string, RealDemo> = {
  flow: flow as unknown as RealDemo,
  orders: orders as unknown as RealDemo,
};
