/// <reference types="vite/client" />

/* eslint-disable @typescript-eslint/no-explicit-any, @typescript-eslint/no-empty-object-type --
 * 环境声明文件：DefineComponent 的三个泛型位（Props / RawBindings / D）在此处
 * 无法给出更精确的类型 —— 具体组件的 props 类型由 vue-tsc 从 .vue 单文件本身推导，
 * 这里的宽松声明只用于让非 vue-tsc 的工具（如 IDE 的裸 TS 服务）能解析 *.vue 导入。
 * 业务代码中的 any 仍然是 error。
 */

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

declare module '*.jpg' {
  const src: string
  export default src
}

declare module '*.jpeg' {
  const src: string
  export default src
}

declare module '*.png' {
  const src: string
  export default src
}

declare module '*.svg' {
  const src: string
  export default src
}

declare module '*.gif' {
  const src: string
  export default src
}

declare module '*.webp' {
  const src: string
  export default src
}

// WangEditor 5 的 Vue 包未在 package exports 中暴露其声明文件，
// TypeScript 6 无法自动解析，组件的业务配置类型仍由 @wangeditor/editor 校验。
declare module '@wangeditor/editor-for-vue' {
  import type { DefineComponent } from 'vue'
  export const Editor: DefineComponent<Record<string, unknown>, Record<string, unknown>, any>
  export const Toolbar: DefineComponent<Record<string, unknown>, Record<string, unknown>, any>
}
