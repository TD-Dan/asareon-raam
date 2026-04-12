-- Actionbus Analyzer
-- Monitors all actions on the bus with full history retrieval on startup.
-- Reports: actions/sec, by feature, by identity, top actions, recent activity.

-- ═══════════════════════════════════════════════════════════
-- STATE
-- ═══════════════════════════════════════════════════════════

total_actions = 0
by_feature = {}
by_identity = {}
by_action = {}
last_actions = {}
max_last = 25
start_time = 0

-- ═══════════════════════════════════════════════════════════
-- HELPERS
-- ═══════════════════════════════════════════════════════════

function increment(tbl, key)
    tbl[key] = (tbl[key] or 0) + 1
end

function get_feature(action_name)
    local dot = string.find(action_name, "%.")
    if dot then return string.sub(action_name, 1, dot - 1) end
    return action_name
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

-- ═══════════════════════════════════════════════════════════
-- COUNTING
-- ═══════════════════════════════════════════════════════════

function count_action(name, originator)
    total_actions = total_actions + 1
    increment(by_feature, get_feature(name))
    increment(by_action, name)
    if originator then
        increment(by_identity, originator)
    end
    local entry = name
    if originator then entry = entry .. " [" .. originator .. "]" end
    table.insert(last_actions, entry)
    if #last_actions > max_last then table.remove(last_actions, 1) end
end

-- ═══════════════════════════════════════════════════════════
-- REPORT
-- ═══════════════════════════════════════════════════════════

function print_report()
    local uptime = raam.time() - start_time
    local rate = 0
    if uptime > 1000 then
        rate = math.floor(total_actions / (uptime / 1000) * 10) / 10
    end

    raam.console.clear()
    raam.console.print("ACTIONBUS ANALYZER", "#7C4DFF", true, false)
    raam.console.print(
        total_actions .. " actions | " ..
        format_duration(uptime) .. " uptime | " ..
        rate .. " act/s",
        nil, false, false
    )
    raam.console.print("")

    -- Feature breakdown
    raam.console.print("By Feature", "#64B5F6", true, false)
    for _, entry in ipairs(sorted_pairs(by_feature)) do
        local pct = math.floor(entry.count / total_actions * 100)
        raam.console.print("  " .. entry.key .. ": " .. entry.count .. " (" .. pct .. "%)")
    end

    -- Identity breakdown (top 10)
    raam.console.print("")
    raam.console.print("By Identity (top 10)", "#64B5F6", true, false)
    local id_sorted = sorted_pairs(by_identity)
    for i = 1, math.min(10, #id_sorted) do
        raam.console.print("  " .. id_sorted[i].key .. ": " .. id_sorted[i].count)
    end

    -- Top 15 actions
    raam.console.print("")
    raam.console.print("Top 15 Actions", "#64B5F6", true, false)
    local top = sorted_pairs(by_action)
    for i = 1, math.min(15, #top) do
        raam.console.print("  " .. top[i].key .. ": " .. top[i].count)
    end

    -- Recent activity
    raam.console.print("")
    raam.console.print("Last " .. #last_actions .. " Actions", "#64B5F6", true, false)
    for i, entry in ipairs(last_actions) do
        raam.console.print("  " .. i .. ". " .. entry, "#9E9E9E")
    end

    -- Registry stats
    local catalog = raam.actions()
    local public_count = 0
    local feat_set = {}
    for _, a in ipairs(catalog) do
        if a.public then public_count = public_count + 1 end
        feat_set[a.feature] = true
    end
    local feat_count = 0
    for _ in pairs(feat_set) do feat_count = feat_count + 1 end

    raam.console.print("")
    raam.console.print(
        "Registry: " .. #catalog .. " actions (" .. public_count .. " public) across " .. feat_count .. " features",
        "#9E9E9E", false, true
    )
end

-- ═══════════════════════════════════════════════════════════
-- LIFECYCLE
-- ═══════════════════════════════════════════════════════════

function on_load()
    start_time = raam.time()

    -- Retrieve all historical actions from before this script loaded
    local history = raam.actionbus.retrieve({ limit = 2000 })
    for _, entry in ipairs(history) do
        count_action(entry.name, entry.originator)
    end
    raam.log(raam.scriptName .. " loaded — ingested " .. #history .. " historical actions")

    -- Subscribe to live actions from this point forward
    raam.actionbus.listen("*", function(name, payload, originator)
        count_action(name, originator)
    end)

    -- Report every 30 seconds
    raam.interval(30000, print_report)

    -- Initial report after brief startup delay
    raam.delay(2000, print_report)
end
