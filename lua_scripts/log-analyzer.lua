-- Log Analyzer
-- Monitors the application log for warnings, errors, and patterns.
-- Retrieves historical logs on startup, then subscribes to live events.

-- ═══════════════════════════════════════════════════════════
-- STATE
-- ═══════════════════════════════════════════════════════════

start_time = 0
total_logs = 0
by_level = { DEBUG = 0, INFO = 0, WARN = 0, ERROR = 0, FATAL = 0 }
by_tag = {}
recent_warnings = {}
recent_errors = {}
max_recent = 10

-- ═══════════════════════════════════════════════════════════
-- HELPERS
-- ═══════════════════════════════════════════════════════════

function increment(tbl, key)
    tbl[key] = (tbl[key] or 0) + 1
end

function push_recent(list, item)
    table.insert(list, item)
    if #list > max_recent then table.remove(list, 1) end
end

function sorted_pairs(tbl)
    local entries = {}
    for k, v in pairs(tbl) do
        table.insert(entries, { key = k, count = v })
    end
    table.sort(entries, function(a, b) return a.count > b.count end)
    return entries
end

function format_duration(ms)
    local secs = math.floor(ms / 1000)
    local mins = math.floor(secs / 60)
    secs = secs % 60
    if mins > 0 then return mins .. "m " .. secs .. "s" end
    return secs .. "s"
end

function truncate(str, max_len)
    if #str <= max_len then return str end
    return string.sub(str, 1, max_len - 3) .. "..."
end

-- ═══════════════════════════════════════════════════════════
-- LOG PROCESSING
-- ═══════════════════════════════════════════════════════════

function process_log(level, tag, message)
    total_logs = total_logs + 1
    by_level[level] = (by_level[level] or 0) + 1
    increment(by_tag, tag)

    if level == "WARN" then
        push_recent(recent_warnings, tag .. ": " .. truncate(message, 80))
    elseif level == "ERROR" or level == "FATAL" then
        push_recent(recent_errors, tag .. ": " .. truncate(message, 80))
    end
end

-- ═══════════════════════════════════════════════════════════
-- REPORT
-- ═══════════════════════════════════════════════════════════

function print_report()
    local uptime = raam.time() - start_time

    raam.console.clear()
    raam.console.print("LOG ANALYZER", "#FF9800", true, false)
    raam.console.print(
        total_logs .. " log entries | " .. format_duration(uptime) .. " uptime",
        nil, false, false
    )
    raam.console.print("")

    -- Level breakdown
    raam.console.print("By Level", "#64B5F6", true, false)
    if by_level.DEBUG > 0 then
        raam.console.print("  DEBUG: " .. by_level.DEBUG, "#9E9E9E")
    end
    if by_level.INFO > 0 then
        raam.console.print("  INFO:  " .. by_level.INFO)
    end
    if by_level.WARN > 0 then
        raam.console.print("  WARN:  " .. by_level.WARN, "#FFA726")
    end
    if by_level.ERROR > 0 then
        raam.console.print("  ERROR: " .. by_level.ERROR, "#FF5252", true)
    end
    if by_level.FATAL > 0 then
        raam.console.print("  FATAL: " .. by_level.FATAL, "#FF1744", true)
    end

    -- Top logging sources
    raam.console.print("")
    raam.console.print("Top Sources (by volume)", "#64B5F6", true, false)
    local tag_sorted = sorted_pairs(by_tag)
    for i = 1, math.min(10, #tag_sorted) do
        local pct = math.floor(tag_sorted[i].count / total_logs * 100)
        raam.console.print("  " .. tag_sorted[i].key .. ": " .. tag_sorted[i].count .. " (" .. pct .. "%)")
    end

    -- Recent errors
    if #recent_errors > 0 then
        raam.console.print("")
        raam.console.print("Recent Errors (" .. by_level.ERROR + (by_level.FATAL or 0) .. " total)", "#FF5252", true, false)
        for _, err in ipairs(recent_errors) do
            raam.console.print("  " .. err, "#FF8A80")
        end
    end

    -- Recent warnings
    if #recent_warnings > 0 then
        raam.console.print("")
        raam.console.print("Recent Warnings (" .. by_level.WARN .. " total)", "#FFA726", true, false)
        for i = math.max(1, #recent_warnings - 4), #recent_warnings do
            raam.console.print("  " .. recent_warnings[i], "#FFCC80")
        end
    end

    -- Health summary
    raam.console.print("")
    local error_rate = 0
    if total_logs > 0 then
        error_rate = math.floor((by_level.ERROR + (by_level.FATAL or 0)) / total_logs * 1000) / 10
    end
    if error_rate > 5 then
        raam.console.print("Health: " .. error_rate .. "% error rate", "#FF5252", true)
    elseif error_rate > 1 then
        raam.console.print("Health: " .. error_rate .. "% error rate", "#FFA726")
    else
        raam.console.print("Health: " .. error_rate .. "% error rate — OK", "#4CAF50")
    end
end

-- ═══════════════════════════════════════════════════════════
-- LIFECYCLE
-- ═══════════════════════════════════════════════════════════

function on_load()
    start_time = raam.time()

    -- Retrieve historical logs (all levels for full picture)
    local history = raam.applog.retrieve({ limit = 1000, minLevel = "DEBUG" })
    for _, entry in ipairs(history) do
        process_log(entry.level, entry.tag, entry.message)
    end
    raam.log(raam.scriptName .. " loaded — ingested " .. #history .. " historical log entries")

    -- Subscribe to live log events (WARN and above for efficiency)
    raam.applog.listen("INFO", function(level, tag, message, timestamp)
        process_log(level, tag, message)
    end)

    -- Report every 30 seconds
    raam.interval(30000, print_report)

    -- Initial report after brief startup delay
    raam.delay(2000, print_report)
end
