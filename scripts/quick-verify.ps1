# 批 87 快速验证脚本 (Windows PowerShell)
# 用途：验证前端优化和数据库索引是否生效
# 使用方法：.\scripts\quick-verify.ps1

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "批 87 优化验证脚本" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

$ErrorActionPreference = "Continue"

# 步骤 1：检查前端构建速度
Write-Host "步骤 1/5：验证前端构建速度（目标 < 10 秒）" -ForegroundColor Yellow
Write-Host "----------------------------------------"
Set-Location devops-platform-frontend

Write-Host "清理缓存..."
if (Test-Path "node_modules\.vite") {
    Remove-Item -Path "node_modules\.vite" -Recurse -Force
}

Write-Host "开始构建..."
$buildStart = Get-Date
pnpm run build | Out-Null
$buildEnd = Get-Date
$buildTime = ($buildEnd - $buildStart).TotalSeconds

if ($buildTime -lt 10) {
    Write-Host "✅ 构建速度：$([math]::Round($buildTime, 2))s（目标达成）" -ForegroundColor Green
} else {
    Write-Host "❌ 构建速度：$([math]::Round($buildTime, 2))s（超过目标 10s）" -ForegroundColor Red
}
Write-Host ""

Set-Location ..

# 步骤 2：检查未使用依赖是否已清理
Write-Host "步骤 2/5：验证死代码清理" -ForegroundColor Yellow
Write-Host "----------------------------------------"
Set-Location devops-platform-frontend

$packageJson = Get-Content "package.json" | ConvertFrom-Json
$hasWangEditor = $packageJson.dependencies.PSObject.Properties.Name -contains "@wangeditor/editor" -or
                 $packageJson.dependencies.PSObject.Properties.Name -contains "@wangeditor/editor-for-vue"

if (-not $hasWangEditor) {
    Write-Host "✅ 未使用依赖已清理" -ForegroundColor Green
} else {
    Write-Host "❌ 仍有 @wangeditor 相关依赖未清理" -ForegroundColor Red
}
Write-Host ""

Set-Location ..

# 步骤 3：验证数据库索引
Write-Host "步骤 3/5：验证数据库索引（需要 Docker 运行）" -ForegroundColor Yellow
Write-Host "----------------------------------------"

$dockerRunning = docker ps 2>$null
if (-not $dockerRunning) {
    Write-Host "❌ Docker 未运行，跳过数据库验证" -ForegroundColor Red
    Write-Host ""
    $indexCount = 0
    $alertIndexCount = 0
    $approvalIndexCount = 0
} else {
    # 检查 PostgreSQL 容器
    $pgContainer = docker ps --filter "name=postgres" --format "{{.Names}}" 2>$null | Select-Object -First 1

    if (-not $pgContainer) {
        Write-Host "⚠️  PostgreSQL 容器未找到" -ForegroundColor Yellow
        Write-Host "提示：执行以下命令重建数据库："
        Write-Host "  docker-compose down -v"
        Write-Host "  docker-compose up -d"
        Write-Host ""
        $indexCount = 0
        $alertIndexCount = 0
        $approvalIndexCount = 0
    } else {
        Write-Host "检查工单表索引..."
        $indexCount = docker exec $pgContainer psql -U opsbrain -d opsbrain_db -tAc "SELECT COUNT(*) FROM pg_indexes WHERE tablename = 'sys_devops_ticket' AND indexname LIKE 'idx_%'" 2>$null
        $indexCount = [int]$indexCount

        if ($indexCount -ge 10) {
            Write-Host "✅ 工单表索引数量：${indexCount}（预期 >= 10）" -ForegroundColor Green
        } else {
            Write-Host "❌ 工单表索引数量：${indexCount}（不足 10）" -ForegroundColor Red
            Write-Host "提示：可能需要重建数据库以应用新索引"
        }

        Write-Host ""
        Write-Host "检查告警表索引..."
        $alertIndexCount = docker exec $pgContainer psql -U opsbrain -d opsbrain_db -tAc "SELECT COUNT(*) FROM pg_indexes WHERE tablename = 'sys_alert' AND indexname LIKE 'idx_%'" 2>$null
        $alertIndexCount = [int]$alertIndexCount

        if ($alertIndexCount -ge 5) {
            Write-Host "✅ 告警表索引数量：${alertIndexCount}（预期 >= 5）" -ForegroundColor Green
        } else {
            Write-Host "❌ 告警表索引数量：${alertIndexCount}（不足 5）" -ForegroundColor Red
        }

        Write-Host ""
        Write-Host "检查审批表索引..."
        $approvalIndexCount = docker exec $pgContainer psql -U opsbrain -d opsbrain_db -tAc "SELECT COUNT(*) FROM pg_indexes WHERE tablename = 'sys_approval_request' AND indexname LIKE 'idx_%'" 2>$null
        $approvalIndexCount = [int]$approvalIndexCount

        if ($approvalIndexCount -ge 4) {
            Write-Host "✅ 审批表索引数量：${approvalIndexCount}（预期 >= 4）" -ForegroundColor Green
        } else {
            Write-Host "❌ 审批表索引数量：${approvalIndexCount}（不足 4）" -ForegroundColor Red
        }
        Write-Host ""
    }
}

# 步骤 4：检查前端测试
Write-Host "步骤 4/5：验证前端测试" -ForegroundColor Yellow
Write-Host "----------------------------------------"
Set-Location devops-platform-frontend

$testResult = pnpm test 2>&1 | Out-String
$testPassed = $testResult -match "1044 passed"

if ($testPassed) {
    Write-Host "✅ 测试通过：1044/1044" -ForegroundColor Green
} else {
    $testSummary = ($testResult -split "`n" | Select-String "Tests.*passed" | Select-Object -First 1).ToString().Trim()
    Write-Host "⚠️  测试结果：$testSummary" -ForegroundColor Yellow
}
Write-Host ""

Set-Location ..

# 步骤 5：生成验证报告
Write-Host "步骤 5/5：生成验证报告" -ForegroundColor Yellow
Write-Host "----------------------------------------"

$reportFile = "docs\reports\report-87-verification-result.md"
$timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"

$reportContent = @"
# 批 87 验证结果报告

**日期**: $timestamp
**验证人**: 自动化脚本

---

## 验证结果

### 1. 前端构建速度
- **构建时间**: $([math]::Round($buildTime, 2))s
- **目标**: < 10s
- **状态**: $(if ($buildTime -lt 10) { "✅ 通过" } else { "❌ 未通过" })

### 2. 死代码清理
- **@wangeditor 依赖**: $(if (-not $hasWangEditor) { "已清理" } else { "未清理" })
- **状态**: $(if (-not $hasWangEditor) { "✅ 通过" } else { "❌ 未通过" })

### 3. 数据库索引
- **工单表索引数量**: $(if ($indexCount -gt 0) { $indexCount } else { "未检测" })
- **告警表索引数量**: $(if ($alertIndexCount -gt 0) { $alertIndexCount } else { "未检测" })
- **审批表索引数量**: $(if ($approvalIndexCount -gt 0) { $approvalIndexCount } else { "未检测" })
- **状态**: $(if ($indexCount -ge 10) { "✅ 通过" } else { "⚠️  待重建数据库" })

### 4. 前端测试
- **测试结果**: $(if ($testPassed) { "1044/1044 通过" } else { "部分通过" })
- **状态**: $(if ($testPassed) { "✅ 通过" } else { "⚠️  部分通过" })

---

## 下一步建议

"@

$allPassed = ($buildTime -lt 10) -and (-not $hasWangEditor) -and ($indexCount -ge 10) -and $testPassed

if ($allPassed) {
    $reportContent += @"
**所有验证项通过！** 🎉

建议继续执行：
1. 前端性能监控（1 小时）
2. 骨架屏加载（2 小时）
3. 接口性能压测（2 小时）

详见：``docs/reports/report-87-next-steps-recommendation.md``
"@
} else {
    $reportContent += "**部分验证项未通过，建议检查：**`n`n"

    if ($buildTime -ge 10) {
        $reportContent += "- [ ] 前端构建速度超过目标（$([math]::Round($buildTime, 2))s > 10s）`n"
    }

    if ($hasWangEditor) {
        $reportContent += "- [ ] @wangeditor 依赖未清理完全`n"
    }

    if ($indexCount -lt 10) {
        $reportContent += "- [ ] 数据库索引未应用，执行：``docker-compose down -v && docker-compose up -d```n"
    }

    if (-not $testPassed) {
        $reportContent += "- [ ] 前端测试未完全通过`n"
    }
}

$reportContent | Out-File -FilePath $reportFile -Encoding UTF8

Write-Host "✅ 验证报告已生成：$reportFile" -ForegroundColor Green
Write-Host ""

# 总结
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "验证完成！" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "详细报告：$reportFile"
Write-Host ""

if ($allPassed) {
    Write-Host "🎉 所有验证项通过！" -ForegroundColor Green
    Write-Host ""
    Write-Host "下一步："
    Write-Host "  1. 前端性能监控（见 report-87-next-steps-recommendation.md）"
    Write-Host "  2. 骨架屏加载"
    Write-Host "  3. 接口性能压测"
} else {
    Write-Host "⚠️  部分验证项未通过，请检查报告" -ForegroundColor Yellow

    if ($indexCount -lt 10) {
        Write-Host ""
        Write-Host "建议立即执行（重建数据库以应用索引）："
        Write-Host "  docker-compose down -v"
        Write-Host "  docker-compose up -d"
    }
}

Write-Host ""
