(ns clj-roguelike.dungeon
  (:require [clj-roguelike.random :refer [rand-range perc-chance]]))

(def additional-tunnel-perc 10)

(def current-id (atom 0))
(defn new-id []
  (swap! current-id inc))

(defn create-area [w h]
  {:width w
   :height h
   :tiles (vec (repeat (* w h) {:tile :wall}))})

(defn- yx->i [w [y x]]
  (if (< -1 x w)
    (+ x (* y w))
    -1))

(defn- i->yx [w i]
  [(int (/ i w)) (mod i w)])

(defn- i->tile [index area]
  (:tile (nth (:tiles area) index nil)))

(defn- i->m [index area]
  (nth (:tiles area) index nil))

(defn- yx->tile [yx area]
  (i->tile (yx->i (:width area) yx) area))

(defn- yx->m [yx area]
  (i->m (yx->i (:width area) yx) area))

(defn- coord-range [c d]
  (map #(+ c %) (range 0 d)))

(defn- indexes-rect [w h [y x] width]
  (let [ys (coord-range y h) 
        xs (coord-range x w)]
    (for [y ys x xs] 
      (yx->i width [y x])))) 

(defn- indexes->tiles [tiles indexes]
  (map #(nth tiles % nil) indexes))

(defn- carve-tile [id area index]
  (-> area
      (assoc-in [:tiles index :tile] :empty)
      (assoc-in [:tiles index :id] id)))

(defn- carve-tile-indexes [indexes id area]
  (reduce (partial carve-tile id) area indexes))

(defn- within-boundaries? [w h [y x] area]
  (and (<= (+ h y) (:height area))
       (<= (+ w x) (:width area))))

(defn- none-empty? [tiles indexes]
  (not-any? #(= :empty (:tile %)) 
            (indexes->tiles tiles indexes)))

(defn add-room [w h yx area]
  (let [indexes (indexes-rect w h yx (:width area))
        boundary (indexes-rect (+ 2 w) (+ 2 h) (mapv dec yx) (:width area))]
    (if (and (within-boundaries? w h yx area)
             (none-empty? (:tiles area) boundary))
      (carve-tile-indexes indexes (new-id) area)
      area)))

(defn- rand-coord [area]
  [(rand-int (:height area))
   (rand-int (:width area))])

(defn- random-room [area _]
  (add-room (rand-range 2 9)
            (rand-range 2 9)
            (rand-coord area)
            area))

(defn- adjacent-tiles [index area]
  (let [w (:width area)
        [y x] (i->yx w index)]
    (->> [[(dec y) x]     ; North
          [y (inc x)]     ; East
          [(inc y) x]     ; South
          [y (dec x)]]    ; West 
         (map (partial yx->i w))
         (filter (complement neg?))))) 

(defn- edge-tile? [index area]
  (and (= :empty (i->tile index area))
       (some #(= :wall (i->tile % area))
             (adjacent-tiles index area))))

(defn- edge-tiles [area]
  (keep-indexed (fn [index _] (when (edge-tile? index area) index))
                (:tiles area)))

(defn- corner-tile? [index area]
  (= 2 (->> (map #(= :wall (i->tile % area))
                 (adjacent-tiles index area))
            (filter true?)
            count)))

(defn- adjacent-walls [index area]
  (filter #(= :wall (i->tile % area)) 
          (adjacent-tiles index area)))

(defn- walk-until [pred [w h] yxs dir]
  (let [next-yx (mapv + (first yxs) dir)
        next-i (yx->i w next-yx)]
    (cond 
      (or (neg? next-i)
          (<= (* w h) next-i)) nil
      (pred next-yx) (cons next-yx yxs)
      :else (recur pred [w h] (cons next-yx yxs) dir))))

(defn- trace-corridors [index area]
  (let [w (:width area)
        yx (i->yx w index)]
    (->> area
         (adjacent-walls index)
         (map (partial i->yx w))
         (map #(mapv - %2 %1) (repeat yx))
         (map (partial walk-until 
                       #(= :empty (yx->tile % area))
                       [w (:height area)]
                       [yx])))))

(defn- same-id? [yx1 yx2 area]
  (= (:id (yx->m yx1 area))
     (:id (yx->m yx2 area))))

(defn- swap-id [old new area]
  (assoc area 
         :tiles 
         (mapv #(if (= old (:id %))
                 (assoc % :id new)
                 %)
              (:tiles area))))

(defn- create-corridor [area yxs]
  (let [ayx (first yxs)
        byx (last yxs)
        old-id (:id (yx->m ayx area))
        id (:id (yx->m byx area))
        indexes (map (partial yx->i (:width area)) 
                     (drop-last (rest yxs)))]
    (if (same-id? ayx byx area)
      (if (perc-chance additional-tunnel-perc)
        (carve-tile-indexes indexes id area)
        area)
      (->> area
           (carve-tile-indexes indexes id)
           (swap-id old-id id)))))

(defn- create-corridors [area index]
  (let [lyxs (filter seq? (trace-corridors index area))]
    (reduce create-corridor area lyxs)))

(defn- generate-rooms [room-attempts area]
  (reduce random-room area (repeat room-attempts nil)))

(defn- generate-corridors [area]
  (->> area
       edge-tiles 
       shuffle
       (reduce create-corridors area)))

(defn generate-dungeon [w h room-attempts]
  (->> (create-area w h)
       (generate-rooms room-attempts)
       (generate-corridors)))

(defn pretty-print [area]
  (->> (map :tile (:tiles area))
       (map #(cond (= % :wall) :#
                   (= % :empty) :.))
       (partition (:width area))
       clojure.pprint/pprint))
