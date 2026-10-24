import { GripVertical } from 'lucide-react';
import type { NodeKind } from '../api/types';
import { INSERT_KINDS } from '../flow/edit';

/** Тип данных перетаскивания из палитры: так холст отличает блок палитры от файлов и прочего. */
export const PALETTE_MIME = 'application/x-blueprints-kind';

interface Props {
  /** Клик по виду блока (без перетаскивания) — вставить после выделенного блока. */
  onPick: (kind: NodeKind) => void;
  /** Есть выделенный блок, после которого можно вставить по клику. */
  canPick: boolean;
}

/**
 * Палитра блоков над блок-схемой: перетащите вид блока на блок схемы — новый встанет после него
 * (POST /graph/ops insertAfter, как «Вставить после» в инспекторе); код блока вводится в инспекторе.
 */
export function BlockPalette({ onPick, canPick }: Props) {
  return (
    <div className="palette" role="toolbar" aria-label="Палитра блоков">
      <span className="palette-title">Блоки</span>
      {INSERT_KINDS.map((k) => (
        <button
          key={k.kind}
          className={`palette-item kind-${k.kind}`}
          draggable
          onDragStart={(e) => {
            e.dataTransfer.setData(PALETTE_MIME, k.kind);
            e.dataTransfer.effectAllowed = 'copy';
          }}
          onClick={() => onPick(k.kind)}
          title={canPick ? `${k.label}: перетащите на блок схемы или нажмите — вставится после выделенного` : `${k.label}: перетащите на блок схемы — вставится после него`}
        >
          <GripVertical size={12} className="palette-grip" />{k.label}
        </button>
      ))}
    </div>
  );
}
