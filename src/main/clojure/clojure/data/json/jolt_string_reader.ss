;; Generic StringPBR token decoder; not a WAL-specific parser.
;; No input/cursor mutation. Valid Unicode escapes use Jolt scalar semantics;
;; malformed/noncanonical escapes decline for original error/position behavior.
;; Scan/validate first, then allocate the escaped result exactly once. The
;; second scan fills only that private string; ordinary copied runs are disjoint.
;; Plain tokens keep the substring fast path. No growing output port or scratch
;; buffer is retained, and malformed input declines before output allocation.
(lambda (s start)
  (let ((n (string-length s)))
    (define (hex-unit-at start)
      (and (fx<= (fx+ start 4) n)
           (let hex ((i start) (end (fx+ start 4)) (value 0))
             (if (fx= i end) value
                 (let* ((cp (char->integer (string-ref s i)))
                        (digit (cond
                                 ((fx<= 48 cp 57) (fx- cp 48))
                                 ((fx<= 65 cp 70) (fx- cp 55))
                                 ((fx<= 97 cp 102) (fx- cp 87))
                                 (else #f))))
                   (and digit (hex (fx+ i 1) end (fx+ (fx* value 16) digit))))))))
    (define (unicode-at slash)
      (let ((head (hex-unit-at (fx+ slash 2))))
        (and head
             (cond
               ((fx<= #xd800 head #xdbff)
                (and (fx<= (fx+ slash 12) n)
                     (char=? (string-ref s (fx+ slash 6)) #\\)
                     (char=? (string-ref s (fx+ slash 7)) #\u)
                     (let ((tail (hex-unit-at (fx+ slash 8))))
                       (and tail (fx<= #xdc00 tail #xdfff)
                            (cons (integer->char
                                    (fx+ #x10000 (fx* (fx- head #xd800) #x400)
                                         (fx- tail #xdc00)))
                                  (fx+ slash 12))))))
               ((fx<= #xdc00 head #xdfff) #f)
               (else (cons (integer->char head) (fx+ slash 6)))))))
    (define (escape-at slash)
      (if (fx>= (fx+ slash 1) n) (values #f n)
          (let* ((escape (string-ref s (fx+ slash 1)))
                 (unicode (and (char=? escape #\u) (unicode-at slash))))
            (values
              (if unicode (car unicode)
                  (case escape
                    ((#\" #\\ #\/) escape)
                    ((#\b) (integer->char 8))
                    ((#\f) (integer->char 12))
                    ((#\n) #\newline)
                    ((#\r) #\return)
                    ((#\t) #\tab)
                    (else #f)))
              (if unicode (cdr unicode) (fx+ slash 2))))))
    (define (extract end size)
      (let ((out (make-string size)))
        (let copy ((i start) (run start) (target 0))
          (cond
            ((fx= i end)
             ;; Chez's primitive takes source, source index, destination,
             ;; destination index, count (not the R6RS destination-first API).
             (when (fx< run i) (string-copy! s run out target (fx- i run)))
             (jolt-vector out (fx+ end 1)))
            ((char=? (string-ref s i) #\\)
             (call-with-values (lambda () (escape-at i))
               (lambda (decoded next)
                 (let ((at (fx+ target (fx- i run))))
                   (when (fx< run i) (string-copy! s run out target (fx- i run)))
                   (string-set! out at decoded)
                   (copy next next (fx+ at 1))))))
            (else (copy (fx+ i 1) run target))))))
    (let scan ((i start) (size 0) (escaped? #f))
      (if (fx>= i n) #f
          (let ((ch (string-ref s i)))
            (cond
              ((char=? ch #\")
               (if escaped? (extract i size)
                   (jolt-vector (substring s start i) (fx+ i 1))))
              ((char=? ch #\\)
               (call-with-values (lambda () (escape-at i))
                 (lambda (decoded next)
                   (and decoded (scan next (fx+ size 1) #t)))))
              (else (scan (fx+ i 1) (fx+ size 1) escaped?))))))))
