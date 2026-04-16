-- session-ticker
-- Asareon Raam Lua Script

-- Simple script that posts "TICK" on specified session with specified interval.
-- Great for keeping the session alive with automatic turns enabled on agent(s)

tick_interval_s = 120 -- 2 minute interval
session_name = "session-name-here" -- replace with the session name, handle or uuid

function on_load()
    raam.console.clear()
    raam.log("session-ticker loaded")
    raam.interval(tick_interval_s * 1000, on_tick)
end

function on_tick()
    raam.log("tick")
    raam.dispatch("session.POST", { session=session_name,message="TICK" })
end
