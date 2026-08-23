(ns ivylee.hlc
  "Hybrid logical clocks (Kulkarni et al.).
  A clock is [physical-ms logical-counter node-id]; lexicographic vector
  comparison gives the total order, with node-id as final tie-breaker.")


(defn init
  [node-id]
  [0 0 node-id])


(defn before?
  [a b]
  (neg? (compare a b)))


(defn tick
  "Advance `clock` for a local write at wall time `now-ms`.
  Strictly monotonic even if the wall clock stalls or jumps backwards."
  [[pt c node] now-ms]
  (let [pt' (max pt now-ms)]
    [pt' (if (= pt' pt) (inc c) 0) node]))


(defn recv
  "Advance `clock` after observing `remote` (e.g. during merge) at wall time
  `now-ms`, so stamps issued locally afterwards sort after everything seen."
  [[pt c node] [rpt rc _] now-ms]
  (let [pt' (max pt rpt now-ms)
        c'  (cond
              (= pt' pt rpt) (inc (max c rc))
              (= pt' pt)     (inc c)
              (= pt' rpt)    (inc rc)
              :else          0)]
    [pt' c' node]))
