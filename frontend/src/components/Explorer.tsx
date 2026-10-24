import { useMemo, useState } from 'react';
import { Box, Braces, ChevronRight, FileCode2, Folder, FolderOpen, Hash, Layers, SquareFunction, Wrench } from 'lucide-react';
import type { Finding, Graph, GraphNode } from '../api/types';

interface Props {
  files: { path: string }[];
  activeFile: string | null;
  onOpenFile: (path: string) => void;
  /** Обзорный граф: из него строится структура «класс → члены». */
  overview: Graph | null;
  findings: Finding[];
  selectedId: string | null;
  /** Клик по классу или члену — выделить на обзоре и показать код. */
  onSelectMember: (node: GraphNode) => void;
  /** Двойной клик (или Enter) по методу — открыть его блок-схему. */
  onOpenMethod: (node: GraphNode) => void;
}

const TYPE_KINDS = new Set(['class', 'interface', 'enum', 'record']);
const CALLABLE = new Set(['method', 'constructor']);

function MemberIcon({ node }: { node: GraphNode }) {
  switch (node.kind) {
    case 'interface': return <Layers size={15} className="icon-interface" />;
    case 'enum': return <Braces size={15} className="icon-enum" />;
    case 'class': case 'record': return <Box size={15} className="icon-class" />;
    case 'constructor': return <Wrench size={15} className="icon-ctor" />;
    case 'method': return <SquareFunction size={15} className="icon-method" />;
    default: return <Hash size={15} className="icon-field" />;
  }
}

/** Число находок, попадающих в строки узла (метод, поле, тип). */
function countIn(node: GraphNode, findings: Finding[]): number {
  const r = node.range;
  if (!r) return 0;
  return findings.filter((f) => f.file === r.file && f.startLine >= r.startLine && f.startLine <= r.endLine).length;
}

/**
 * Проводник как в IDE: файлы проекта и структура кода (типы и их члены, вложенные типы — внутри).
 * Методы с проблемами помечены счётчиком; двойной клик по методу открывает блок-схему.
 */
export function Explorer({ files, activeFile, onOpenFile, overview, findings, selectedId, onSelectMember, onOpenMethod }: Props) {
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const [filesOpen, setFilesOpen] = useState(true);

  const children = useMemo(() => {
    const map = new Map<string | null, GraphNode[]>();
    for (const n of overview?.nodes ?? []) {
      const key = n.parent ?? null;
      map.set(key, [...(map.get(key) ?? []), n]);
    }
    // В коде по порядку строк: так структура совпадает с тем, что видно в редакторе.
    for (const list of map.values()) list.sort((a, b) => (a.range?.startLine ?? 0) - (b.range?.startLine ?? 0));
    return map;
  }, [overview]);

  const toggle = (id: string) =>
    setCollapsed((s) => {
      const next = new Set(s);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  const renderNode = (node: GraphNode) => {
    const kids = children.get(node.id) ?? [];
    const isType = TYPE_KINDS.has(node.kind);
    const open = !collapsed.has(node.id);
    const count = isType ? 0 : countIn(node, findings);
    const callable = CALLABLE.has(node.kind);
    return (
      <li key={node.id}>
        <div
          className={`tree-row ${selectedId === node.id ? 'active' : ''}`}
          role="treeitem"
          aria-expanded={kids.length ? open : undefined}
          tabIndex={0}
          title={callable ? `${node.code ?? node.label} — двойной клик откроет блок-схему` : node.code ?? node.label}
          onClick={() => onSelectMember(node)}
          onDoubleClick={() => callable && onOpenMethod(node)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') (callable ? onOpenMethod : onSelectMember)(node);
            if ((e.key === 'ArrowLeft' && open) || (e.key === 'ArrowRight' && !open)) kids.length && toggle(node.id);
          }}
        >
          {kids.length ? (
            <ChevronRight size={14} className={`chev ${open ? 'open' : ''}`} onClick={(e) => { e.stopPropagation(); toggle(node.id); }} />
          ) : <span style={{ width: 14, flex: 'none' }} />}
          <MemberIcon node={node} />
          <span className="label">{node.label}{callable ? '()' : ''}</span>
          {count > 0 && <span className="count" title={`Проблем: ${count}`}>{count}</span>}
        </div>
        {kids.length > 0 && open && <ul className="tree" role="group">{kids.map(renderNode)}</ul>}
      </li>
    );
  };

  const roots = (children.get(null) ?? []).filter((n) => TYPE_KINDS.has(n.kind));

  return (
    <div className="explorer">
      <div className="section-title"><span>Файлы</span></div>
      <ul className="tree" role="tree" aria-label="Файлы проекта">
        <li>
          <div className="tree-row" role="treeitem" aria-expanded={filesOpen} tabIndex={0}
            onClick={() => setFilesOpen((o) => !o)} onKeyDown={(e) => e.key === 'Enter' && setFilesOpen((o) => !o)}>
            <ChevronRight size={14} className={`chev ${filesOpen ? 'open' : ''}`} />
            {filesOpen ? <FolderOpen size={15} className="icon-folder" /> : <Folder size={15} className="icon-folder" />}
            <span className="label">src</span>
          </div>
          {filesOpen && (
            <ul className="tree" role="group">
              {files.map((f) => (
                <li key={f.path}>
                  <div className={`tree-row ${f.path === activeFile ? 'active' : ''}`} role="treeitem" tabIndex={0}
                    onClick={() => onOpenFile(f.path)} onKeyDown={(e) => e.key === 'Enter' && onOpenFile(f.path)}>
                    <span style={{ width: 14, flex: 'none' }} />
                    <FileCode2 size={15} className="icon-java" />
                    <span className="label">{f.path}</span>
                    {findings.some((x) => x.file === f.path) && (
                      <span className="count" title="Проблем в файле">{findings.filter((x) => x.file === f.path).length}</span>
                    )}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </li>
      </ul>

      {roots.length > 0 && (
        <>
          <div className="section-title"><span>Структура</span></div>
          <ul className="tree" role="tree" aria-label="Структура кода">{roots.map(renderNode)}</ul>
        </>
      )}
    </div>
  );
}
