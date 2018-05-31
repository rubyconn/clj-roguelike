(ns clj-roguelike.dungeon
  (:require [clj-roguelike.random :refer [rand-range perc-chance]]))

(def additional-tunnel-perc 10)
(def min-room-length 2)
(def max-room-length 9)

(def current-id (atom 0))
(defn- new-id []
  (swap! current-id inc))

(defn create-area [w h]
  {:width w
   :height h
   :tiles (vec (repeat (* w h) {:tile :wall}))})

(defn i->yx [w i]
  [(int (/ i w)) (mod i w)])

(defn i->m [index area]
  (nth (:tiles area) index nil))

(defn yx->i [w [y x]]
  (if (< -1 x w)
    (+ x (* y w))
    -1))

(defn yx->m [yx area]
  (i->m (yx->i (:width area) yx) area))

(defn- empty-tile? [area index]
  (= :empty (:tile (i->m index area))))

(defn- wall-tile? [area index]
  (= :wall (:tile (i->m index area))))

(defn- take-nums [start amount]
  (map (partial + start) (range 0 amount)))

(defn- indexes-rect [w h [y x] area-width]
  (let [ys (take-nums y h)
        xs (take-nums x w)]
    (for [y ys x xs]
      (yx->i area-width [y x]))))

(defn- carve-tile [id area index]
  (-> area
      (assoc-in [:tiles index :tile] :empty)
      (assoc-in [:tiles index :id] id)))

(defn- carve-tile-indexes [indexes id area]
  (reduce (partial carve-tile id) area indexes))

(defn- within-boundaries? [w h [y x] area]
  (and (< 0 y (+ h y) (:height area))
       (< 0 x (+ w x) (:width area))))

(defn add-room [w h yx area]
  (let [indexes (indexes-rect w h yx (:width area))
        boundary-idxs (indexes-rect (+ 2 w) (+ 2 h) (mapv dec yx) (:width area))]
    (if (and (within-boundaries? w h yx area)
             (not-any? (partial empty-tile? area) boundary-idxs))
      (carve-tile-indexes indexes (new-id) area)
      area)))

(defn- rand-coord [area]
  [(rand-int (:height area))
   (rand-int (:width area))])

(defn- random-room [area _]
  (add-room (rand-range min-room-length max-room-length)
            (rand-range min-room-length max-room-length)
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

(defn neighboring-tiles [index area]
  (let [w (:width area)
        [y x] (i->yx w index)]
    (->> [[(dec y) x]        ; north
          [(dec y) (inc x)]  ; north-east
          [y (inc x)]        ; east
          [(inc y) (inc x)]  ; south-east
          [(inc y) x]        ; south
          [(inc y) (dec x)]  ; south-west
          [y (dec x)]        ; west
          [(dec y) (dec x)]] ; north-west
         (map (partial yx->i w))
         (filter (complement neg?)))))

(defn edge-tile? [tile-f index area]
  (and (empty-tile? area index)
       (some (partial wall-tile? area)
             (tile-f index area))))

(defn- edge-tiles [area]
  (keep-indexed (fn [index _]
                    (when (edge-tile? adjacent-tiles index area)
                      index))
                (:tiles area)))

(defn- corner-tile? [index area]
  (= 2 (->> (map (partial wall-tile? area)
                 (adjacent-tiles index area))
            (filter true?)
            count)))

(defn- adjacent-walls [index area]
  (filter (partial wall-tile? area)
          (adjacent-tiles index area)))

(defn- walk-until [pred [w h] yxs dir]
  (let [next-yx (mapv + (first yxs) dir)
        next-i (yx->i w next-yx)]
    (cond
      (or (neg? next-i)
          (<= (* w h) next-i)) nil
      (pred next-i) (cons next-yx yxs)
      :else (recur pred [w h] (cons next-yx yxs) dir))))

(defn- trace-corridors [index area]
  (let [w (:width area)
        yx (i->yx w index)]
    (->> area
         (adjacent-walls index)
         (map (partial i->yx w))
         (map #(mapv - %2 %1) (repeat yx))
         (map (partial walk-until
                       (partial empty-tile? area)
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

(defn- connected-dungeon? [area]
  (if-let [ids (seq (->> area
                         :tiles
                         (map :id)
                         (filter number?)))]
    (apply = ids)
    false))

(defn- carved-dungeon? [area]
  (boolean (seq (->> area
                     :tiles
                     (map :tile)
                     (filter #{:empty})))))

(defn- valid-dungeon? [area]
  (and (connected-dungeon? area)
       (carved-dungeon? area)))

(defn- generate-rooms [room-attempts area]
  (reduce random-room area (repeat room-attempts nil)))

(defn- generate-corridors [area]
  (->> area
       edge-tiles
       shuffle
       (reduce create-corridors area)))

(defn generate-dungeon [w h room-attempts]
  (let [dung (->> (create-area w h)
                  (generate-rooms room-attempts)
                  (generate-corridors))]
    (if (valid-dungeon? dung)
      dung
      (recur w h room-attempts))))

(defn pretty-print [area]
  (->> (map :tile (:tiles area))
       (map #(cond (= % :wall) :#
                   (= % :empty) :.))
       (partition (:width area))
       clojure.pprint/pprint))

(defn rand-coord-tile [tile area]
  (let [i (rand-int (* (:width area) (:height area)))]
    (if (= (:tile (nth (:tiles area) i))
           tile)
      (i->yx (:width area) i)
      (recur tile area))))

; For the following code, remember to:
; - Consider moving it to a new file (geometry.clj?)
; - Add doc strings
; - Write pretty-print/illustrative function for complete-pivot data to test it
; - Write actual tests

(defn- add-y [[y x] n] [(+ y n) x])
(defn- add-x [[y x] n] [y (+ x n)])
(defn- sub-y [[y x] n] [(- y n) x])
(defn- sub-x [[y x] n] [y (- x n)])

(defn- pos-line-y [len [y x]]
  (map (fn [rising-y] [rising-y x])
       (range y (+ y len))))
(defn- pos-line-x [len [y x]]
  (map (fn [rising-x] [y rising-x])
       (range x (+ x len))))
(defn- neg-line-y [len [y x]]
  (map (fn [falling-y] [falling-y x])
       (range y (- y len) -1)))
(defn- neg-line-x [len [y x]]
  (map (fn [falling-x] [y falling-x])
       (range x (- x len) -1)))

(defn eighth-pivot [x]
  (reductions #(update %1 %2 inc)
              (vec (repeat x 0))
              (mapcat #(-> % (range x) reverse)
                      (range 1 x))))

(defn apply-pivot-yx
  [pivot-f line-f add-coord-f len origo-yx]
  (mapv (partial mapv add-coord-f)
        (repeat (line-f len origo-yx))
        (pivot-f len)))

(defn complete-pivot [len origo-yx]
  (let [apply-eighth-pivot (partial apply-pivot-yx eighth-pivot)]
    (conj []
          (apply-eighth-pivot neg-line-y add-x len origo-yx)
          (apply-eighth-pivot pos-line-x sub-y len origo-yx)
          (apply-eighth-pivot pos-line-x add-y len origo-yx)
          (apply-eighth-pivot pos-line-y add-x len origo-yx)
          (apply-eighth-pivot pos-line-y sub-x len origo-yx)
          (apply-eighth-pivot neg-line-x add-y len origo-yx)
          (apply-eighth-pivot neg-line-x sub-y len origo-yx)
          (apply-eighth-pivot neg-line-y sub-x len origo-yx))))
