"""Kill any running inference_segformer.py processes."""
import psutil
killed = 0
for p in psutil.process_iter(["pid", "name", "cmdline"]):
    try:
        cl = p.info.get("cmdline") or []
        if any("inference_segformer.py" in str(x) for x in cl):
            print(f"killing pid={p.info['pid']}  cmdline={cl}")
            p.kill()
            killed += 1
    except Exception as e:
        print("skip:", e)
print(f"killed {killed} process(es)")
