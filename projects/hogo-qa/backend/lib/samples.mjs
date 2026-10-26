/**
 * samples.mjs —— 样例数据 API（零依赖）。
 *
 * 扫描 backend/data/samples/ 下的 .xlsx 与 manifest.json，
 * 提供清单查询与文件下载。样例均为合成数据（仓库 samples/make-sample.mjs 生成），
 * 不含任何真实产线数据。
 */

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { extname, join } from 'node:path';
import { config } from './config.mjs';

/** 读取样例清单（每次请求重扫，目录很小；文件被替换后无需重启）。 */
export function listSamples() {
  const out = [];
  let manifest = {};
  try {
    manifest = JSON.parse(readFileSync(join(config.samplesDir, 'manifest.json'), 'utf8'));
  } catch {
    manifest = {};
  }
  let entries = [];
  try {
    entries = readdirSync(config.samplesDir);
  } catch {
    return out;
  }
  for (const name of entries) {
    if (!name.toLowerCase().endsWith('.xlsx')) continue;
    let size = 0;
    try {
      size = statSync(join(config.samplesDir, name)).size;
    } catch {
      continue;
    }
    const meta = manifest[name] || {};
    out.push({
      id: name.replace(/\.xlsx$/i, ''),
      file: name,
      title: meta.title || name,
      description: meta.description || '',
      sizeBytes: size,
      downloadUrl: `/api/samples/${encodeURIComponent(name)}/download`,
    });
  }
  return out;
}

/** 读取样例文件内容；不存在返回 null。 */
export function readSampleFile(fileName) {
  // 文件名白名单校验：只允许目录内已存在的 xlsx（防穿越）。
  const safe = listSamples().find((s) => s.file === fileName);
  if (!safe) return null;
  try {
    return readFileSync(join(config.samplesDir, fileName));
  } catch {
    return null;
  }
}
