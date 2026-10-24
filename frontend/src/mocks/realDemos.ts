// Настоящие графы и находки, снятые с ядра (MethodFlowBuilder, PR #5) и анализатора роли 2 на примерах:
//   flow   — Flow.java: if/else-if, switch без default, while(true)+break/continue, try/catch/finally, метки;
//   orders — OrdersMonolith.java из тестов анализа: циклы, находки O(n²), пустой catch.
// Открываются в режиме моков: npm run dev:mock → http://localhost:5173/?demo=flow (или ?demo=orders).
import type { Diagnostics, Graph } from '../api/types';
import flow from './real/Flow.json';
import orders from './real/OrdersMonolith.json';

export interface RealDemo {
  file: string;
  source: string;
  overview: Graph;
  /** id узла метода из overview → блок-схема. */
  methods: Record<string, Graph>;
  diagnostics: Omit<Diagnostics, 'version'>;
}

export const realDemos: Record<string, RealDemo> = {
  flow: flow as unknown as RealDemo,
  orders: orders as unknown as RealDemo,
};
