(ns crux.fixtures.document-store
  (:require [clojure.test :as t]
            [crux.codec :as c]
            [crux.db :as db]))

(defn test-doc-store [doc-store]
  (let [alice {:crux.db/id :alice, :name "Alice"}
        alice-key (c/new-id alice)
        bob {:crux.db/id :bob, :name "Bob"}
        bob-key (c/new-id bob)
        max-key (c/new-id {:crux.db/id :max, :name "Max"})
        people {alice-key alice, bob-key bob}]

    (db/submit-docs doc-store people)

    (t/is (= {alice-key alice}
             (db/fetch-docs doc-store #{alice-key})))

    (t/is (= people
             (db/fetch-docs doc-store (conj (keys people) max-key))))

    (let [evicted-alice {:crux.db/id :alice, :crux.db/evicted? true}]
      (db/submit-docs doc-store {alice-key evicted-alice})

      (t/is (= {alice-key evicted-alice, bob-key bob}
               (db/fetch-docs doc-store (keys people)))))))
