/**
 * 部署期 API 基址覆盖。
 *
 * 本地 / 后端同源托管时留空（空串 = 沿用同源探测与 127.0.0.1:8004 候选）。
 * 静态站放到 COS + CDN 后，页面与 API 不同源，发布脚本
 * （deploy/cos/publish.ps1）会把本文件改写成：
 *
 *   window.MFG_API_BASE = "https://api.hulkai.cn";
 *
 * 这里只放空串，不要写入真实域名以外的任何信息（尤其不要放密钥）。
 */
window.MFG_API_BASE = "";
