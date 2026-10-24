// ?demo=warehouse — главный пример демо (corpus/06-demo-warehouse, PR #28) на настоящих ядре и анализе.
// Записаны все 8 состояний, достижимых тремя авто-исправлениями O(n²) в любом порядке (HashSet для contains,
// StringBuilder, for-each по LinkedList): исходник, обзор, блок-схемы, диагностика. Каждое состояние скомпилировано
// и запущено на входах корпуса — вывод совпал с версией 1; замеры на bench-входах сняты на настоящей JVM
// (время включает старт JVM). Снимал харнесс ChainDump (JavaParser 3.17, строки «case "X" ->» восстановлены
// после печати — в 3.26 у api этой ошибки нет).
import type { Diagnostics, Graph, RunResult, VerifyReport } from '../api/types';
import data from './real/Warehouse.json';
import type { RealDemo } from './realDemos';

export interface WarehouseState {
  source: string;
  overview: Graph;
  methods: Record<string, Graph>;
  diagnostics: Omit<Diagnostics, 'version'>;
  /** stdin входа корпуса → настоящий результат запуска этого состояния. */
  runs: Record<string, RunResult>;
  /** Версия 1 против этого состояния: входы корпуса + замер на bench-входах. */
  verify: VerifyReport;
}

export type WarehouseTransition = { to: number; description: string; rule: string; diff: string } | { rejected: string };

export const warehouse = data as unknown as {
  file: string;
  states: WarehouseState[];
  /** [состояние][id находки] → куда ведёт исправление. */
  transitions: Record<string, WarehouseTransition>[];
};

export const warehouseDemo = (i: number): RealDemo => {
  const s = warehouse.states[i];
  return { file: warehouse.file, source: s.source, overview: s.overview, methods: s.methods, diagnostics: s.diagnostics };
};

export const WAREHOUSE_ONLY_RECORDED =
  'В демо записаны три исправления O(n²): HashSet для contains, StringBuilder, for-each по LinkedList — остальные работают на живом api';
