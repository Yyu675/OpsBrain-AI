/**
 * orval 配置：项3 Phase B —— OpenAPI 快照 → TS 类型生成（只生成 types）。
 *
 * - input 指仓内快照工件（tools/ci/sync-openapi.sh 从活体 /ai/v3/api-docs 拉取），
 *   生成不依赖活体后端：sync（改契约后）→ gen（随时）两步解耦；
 * - 真产物 = output.schemas → src/api/generated/（传输边界 DTO 类型唯一真相）；
 * - output.target 指向仓库根 tools/orval-out/（弃件，gitignore 豁免）：
 *   orval 无 client:false 语义（v8 schema 查证），client 文件生成后即弃，
 *   不进 src —— knip/vue-tsc/六闸均不可见；
 * - clean:false：gen 入口自管清场（防 orval 跨目录清场误伤）；
 * - 不配 prettier hooks（项目无 prettier 依赖）。
 */
import { defineConfig } from 'orval'

export default defineConfig({
  apiSnapshot: {
    input: { target: '../openapi/api-docs.snapshot.json' },
    output: {
      target: '../tools/orval-out/client.ts',
      schemas: 'src/api/generated',
      indexFiles: false,
      clean: false,
      prettier: false,
      mocks: false
    }
  }
})
