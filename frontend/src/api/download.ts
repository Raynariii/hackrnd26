// GET /export отдаёт zip и итог проверки в заголовках (contracts/openapi.yaml). Без DOM — тесты в node.

export interface ExportFile {
  blob: Blob;
  fileName: string;
  /** «9/9» из X-Verify-Passed — дифф-тесты версии 1 против текущей при экспорте. */
  passed: string | null;
  behaviorPreserved: boolean | null;
}

/** Имя файла из Content-Disposition: filename*=UTF-8''… важнее filename="…". */
export function fileNameFrom(disposition: string | null, fallback: string): string {
  if (!disposition) return fallback;
  const star = /filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)/.exec(disposition);
  if (star) {
    try {
      return decodeURIComponent(star[1].trim());
    } catch {
      /* битая кодировка — пробуем обычный filename */
    }
  }
  const plain = /filename\s*=\s*"([^"]+)"|filename\s*=\s*([^;]+)/.exec(disposition);
  const name = (plain?.[1] ?? plain?.[2] ?? '').trim();
  return name || fallback;
}

export function exportSummary(passed: string | null, preserved: boolean | null): string {
  if (!passed) return '';
  return `${passed} тестов${preserved === null ? '' : preserved ? ', поведение сохранено' : ', поведение изменилось'}`;
}

export function readExportHeaders(headers: Headers, blob: Blob, fallback: string): ExportFile {
  const preserved = headers.get('X-Behavior-Preserved');
  return {
    blob,
    fileName: fileNameFrom(headers.get('Content-Disposition'), fallback),
    passed: headers.get('X-Verify-Passed'),
    behaviorPreserved: preserved === null ? null : preserved === 'true',
  };
}
