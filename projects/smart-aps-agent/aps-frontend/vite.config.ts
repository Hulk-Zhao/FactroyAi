import { readFile, readdir, writeFile } from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { fileURLToPath, URL } from 'node:url'
import { brotliCompress, constants as zlibConstants, gzip } from 'node:zlib'
import { promisify } from 'node:util'

import vue from '@vitejs/plugin-vue'
import type { Plugin } from 'vite'
import { defineConfig, loadEnv } from 'vite'

const gzipAsync = promisify(gzip)
const brotliAsync = promisify(brotliCompress)

/** 只对文本类产物做预压缩；图片/字体本身已压缩，再压只会增加文件数。 */
const COMPRESSIBLE = /\.(?:js|css|html|svg|json)$/
/** 小于 1KB 的文件压缩收益为负（HTTP 头开销 > 节省），跳过。 */
const MIN_COMPRESS_SIZE = 1024

/**
 * 构建后预压缩插件（M3 性能整改）。
 * 用 node:zlib 直接生成 `.gz` / `.br` 兄弟文件，避免新增构建依赖；
 * 静态服务器 / nginx 只要按 Accept-Encoding 命中同名文件即可，无需运行时压缩。
 */
function precompressPlugin(): Plugin {
  let outDir = ''
  return {
    name: 'aps-precompress',
    apply: 'build',
    configResolved(config) {
      outDir = resolve(config.root, config.build.outDir)
    },
    async closeBundle() {
      const files: string[] = []
      async function collect(dir: string): Promise<void> {
        for (const entry of await readdir(dir, { withFileTypes: true })) {
          const full = join(dir, entry.name)
          if (entry.isDirectory()) {
            await collect(full)
            continue
          }
          if (COMPRESSIBLE.test(entry.name)) {
            files.push(full)
          }
        }
      }
      await collect(outDir)
      await Promise.all(
        files.map(async (file) => {
          const buffer = await readFile(file)
          if (buffer.length < MIN_COMPRESS_SIZE) {
            return
          }
          const [gz, br] = await Promise.all([
            gzipAsync(buffer, { level: 9 }),
            brotliAsync(buffer, {
              params: { [zlibConstants.BROTLI_PARAM_QUALITY]: 9 }
            })
          ])
          await Promise.all([writeFile(`${file}.gz`, gz), writeFile(`${file}.br`, br)])
        })
      )
    }
  }
}

export default defineConfig(({ mode }) => {
  // 仅开发态：C27 后端在 security.mode=dev 时要求写请求带 X-API-Key。
  // 密钥来源：进程环境变量 APS_DEV_API_KEY 或 .env*.local（*.local 已被 .gitignore 覆盖）。
  // 未配置时不注入任何头；生产走网关/JWT，不经过该代理，也不会带此头。
  const env = loadEnv(mode, process.cwd(), 'APS_')
  const devApiKey = env.APS_DEV_API_KEY?.trim()
  const devApiHeaders = devApiKey ? { 'X-API-Key': devApiKey } : undefined

  return {
    // 部署基路径：默认站点根；挂在子路径（如 /demo/mfg-agent-platform/）时由
    // 构建参数 APS_BASE 注入，供资源地址与 Vue Router 的 BASE_URL 复用（见 src/router/index.ts）。
    base: process.env.APS_BASE || '/',
    plugins: [vue(), precompressPlugin()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url))
      }
    },
    build: {
      // M3 性能整改：显式按大依赖拆包，避免入口 chunk 把整站视图 + 全量 Element Plus 一起背走。
      rolldownOptions: {
        output: {
          codeSplitting: {
            groups: [
              { name: 'vendor-element-plus', test: /node_modules[\\/]element-plus[\\/]/, priority: 20 },
              { name: 'vendor-echarts', test: /node_modules[\\/](?:echarts|zrender)[\\/]/, priority: 20 },
              { name: 'vendor-flow', test: /node_modules[\\/](?:@vue-flow|@vueuse)[\\/]/, priority: 20 },
              { name: 'vendor-ganttastic', test: /node_modules[\\/]@infectoone[\\/]/, priority: 20 },
              {
                name: 'vendor-vue',
                test: /node_modules[\\/](?:@vue[\\/]|vue[\\/]|vue-router[\\/]|pinia[\\/])/,
                priority: 20
              },
              { name: 'vendor-axios', test: /node_modules[\\/]axios[\\/]/, priority: 20 },
              { name: 'vendor-stomp', test: /node_modules[\\/]@stomp[\\/]/, priority: 20 },
              { name: 'vendor-misc', test: /node_modules[\\/]/, priority: 5 }
            ]
          }
        }
      }
    },
    server: {
      port: 5173,
      proxy: {
        '/api': {
          // 本机联调使用 dev profile 真库后端（18085），避免与其他会话的 8080/1808x 冲突。
          target: 'http://127.0.0.1:18085',
          changeOrigin: true,
          ...(devApiHeaders ? { headers: devApiHeaders } : {})
        },
        '/ws': {
          // C20 求解进度 STOMP：与 /api 同源后端，必须开启 ws 升级。
          target: 'http://127.0.0.1:18085',
          changeOrigin: true,
          ws: true
        }
      }
    }
  }
})
