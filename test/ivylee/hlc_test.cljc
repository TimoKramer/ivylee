(ns ivylee.hlc-test
  (:require
    [clojure.test :refer [deftest is]]
    [clojure.test.check.clojure-test :refer [defspec]]
    [clojure.test.check.generators :as gen]
    [clojure.test.check.properties :as prop]
    [ivylee.hlc :as hlc]))


(def gen-clock
  (gen/tuple gen/nat gen/nat (gen/elements ["A" "B" "C"])))


(defspec tick-is-strictly-monotonic 200
  (prop/for-all [clock gen-clock
                 now   gen/nat]
                ;; holds even when the wall clock (now) is behind the clock's physical part
                (hlc/before? clock (hlc/tick clock now))))


(defspec recv-sorts-after-both-inputs 200
  (prop/for-all [local  gen-clock
                 remote gen-clock
                 now    gen/nat]
                (let [advanced (hlc/recv local remote now)]
                  (and (hlc/before? local advanced)
                       (hlc/before? remote advanced)
                       ;; node identity is preserved
                       (= (last local) (last advanced))))))


(deftest node-id-breaks-ties
  (is (hlc/before? [5 0 "A"] [5 0 "B"]))
  (is (hlc/before? [5 0 "B"] [5 1 "A"])))
