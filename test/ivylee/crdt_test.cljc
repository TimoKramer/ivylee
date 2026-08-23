(ns ivylee.crdt-test
  (:require
    [clojure.test.check.clojure-test :refer [defspec]]
    [clojure.test.check.generators :as gen]
    [clojure.test.check.properties :as prop]
    [ivylee.crdt :as crdt]))


(def gen-hlc
  (gen/tuple gen/nat gen/nat (gen/elements ["A" "B" "C"])))


(defn gen-reg
  [value-gen]
  (gen/fmap (fn [[v t]] {:v v :t t})
            (gen/tuple value-gen gen-hlc)))


(def gen-task
  (gen/hash-map
    :title   (gen-reg gen/string-alphanumeric)
    :list    (gen-reg (gen/one-of [(gen/return :longlist)
                                   (gen/elements ["2026-06-10" "2026-06-11"])]))
    :rank    (gen-reg (gen/fmap double gen/nat))
    :done?   (gen-reg gen/boolean)
    :deleted (gen-reg gen/boolean)))


(def gen-doc
  (gen/fmap (fn [tasks] {:tasks tasks})
            (gen/map gen/nat gen-task {:max-elements 8})))


(defspec merge-is-commutative 200
  (prop/for-all [a gen-doc, b gen-doc]
                (= (crdt/merge-docs a b) (crdt/merge-docs b a))))


(defspec merge-is-associative 200
  (prop/for-all [a gen-doc, b gen-doc, c gen-doc]
                (= (crdt/merge-docs a (crdt/merge-docs b c))
                   (crdt/merge-docs (crdt/merge-docs a b) c))))


(defspec merge-is-idempotent 200
  (prop/for-all [a gen-doc, b gen-doc]
                (let [ab (crdt/merge-docs a b)]
                  (and (= a (crdt/merge-docs a a))
                       (= ab (crdt/merge-docs ab b))
                       (= ab (crdt/merge-docs ab a))))))


(defspec merge-order-never-matters 100
  ;; the property that makes sync correct: any replica seeing any subset of
  ;; docs in any order converges to the same state
  (prop/for-all [docs (gen/vector gen-doc 1 5)]
                (= (reduce crdt/merge-docs docs)
                   (reduce crdt/merge-docs (reverse docs))
                   (reduce crdt/merge-docs (shuffle docs)))))
