/**
 * 工单详情页截图验证驱动（一次性/可复用 dev 工具，不参与打包）。
 *
 * 流程：打开工单 URL → 若撞上内嵌登录闸门则点「前往登录」→ 登录（回跳到
 * 目标工单）→ 等待「工单描述」卡渲染 → 截四张图（首屏/描述卡/活动流/整页）。
 *
 * 用法（在 devops-platform-frontend 目录下执行，playwright 是本目录 devDep）：
 *   node scripts/shot-ticket-detail.cjs [ticketId] [outDir]
 * 默认 ticketId=TKT-20261001-0004，outDir=<系统临时目录>/opsbrain-shots
 *
 * 环境前提：前端 5173、后端 8088 均已启动；账号 admin/admin123。
 * 截图落盘后由调用方（人或 AI）Read 图片核验渲染效果。
 */
const { chromium } = require('playwright')
const fs = require('fs')
const os = require('os')
const path = require('path')

const ticketId = process.argv[2] || 'TKT-20261001-0004'
const outDir = process.argv[3] || path.join(os.tmpdir(), 'opsbrain-shots')
const base = process.env.APP_BASE || 'http://localhost:5173'
const target = `${base}/tickets/${ticketId}`

;(async () => {
  fs.mkdirSync(outDir, { recursive: true })
  const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] })
  const page = await browser.newPage({ viewport: { width: 1440, height: 1200 } })
  const errors = []
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()) })
  page.on('pageerror', e => errors.push(String(e)))

  await page.goto(target, { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(2500)

  // 未登录是内嵌闸门（不是 URL 跳转）：点「前往登录」进登录页再回跳
  const gate = page.locator('button:has-text("前往登录")')
  if (await gate.count()) {
    await gate.first().click()
    await page.waitForTimeout(1500)
  }
  const userInput = page.locator('input[placeholder="用户名"]')
  if (await userInput.count()) {
    await userInput.fill(process.env.APP_USER || 'admin')
    await page.fill('input[placeholder="密码"]', process.env.APP_PASS || 'admin123')
    await page.click('button:has-text("登 录")')
    for (let i = 0; i < 20 && !page.url().includes(ticketId); i++) {
      await page.waitForTimeout(500)
    }
    if (!page.url().includes(ticketId)) {
      await page.goto(target, { waitUntil: 'domcontentloaded' })
    }
  }

  try {
    await page.waitForSelector('.ticket-description-card', { timeout: 20000 })
  } catch (e) {
    await page.screenshot({ path: path.join(outDir, '0-fail.png'), fullPage: true })
    console.log('FAIL url:', page.url())
    console.log('body:', (await page.evaluate(() => document.body.innerText))
      .slice(0, 300).replace(/\n+/g, ' | '))
    throw e
  }
  await page.waitForTimeout(1500)

  await page.screenshot({ path: path.join(outDir, '1-top.png') })

  const desc = page.locator('.ticket-description-card').first()
  await desc.scrollIntoViewIfNeeded()
  await page.waitForTimeout(300)
  await desc.screenshot({ path: path.join(outDir, '2-description.png') })

  const act = page.locator('.activity-list').first()
  await act.scrollIntoViewIfNeeded()
  await page.waitForTimeout(300)
  await act.screenshot({ path: path.join(outDir, '3-activity.png') })

  await page.screenshot({ path: path.join(outDir, '4-full.png'), fullPage: true })

  console.log('url:', page.url())
  console.log('shots:', fs.readdirSync(outDir).join(', '))
  console.log('console errors:', errors.length ? errors.join(' || ') : 'none')
  await browser.close()
  console.log('DONE ->', outDir)
})().catch(e => { console.error('FAIL', e && e.message); process.exit(1) })
