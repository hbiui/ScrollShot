package com.scrollshot;

import android.graphics.Bitmap;
import android.graphics.Canvas;

/**
 * Vertical / horizontal stitching by overlap matching.
 *
 * v1.11 rework, after real-device long images came out as frames
 * stacked with duplicated bands instead of one seamless image.
 * Defects found in the full audit of the old version:
 *  1. Search window was only expected +/- 25% of the frame height.
 *     The real scroll distance regularly differs from the gesture
 *     distance by more than that (fling, nested containers), so the
 *     true overlap was often outside the window and the code fell
 *     back to the guessed overlap.
 *  2. Matching used a 160px-wide grayscale, sampled every 2nd row
 *     and column, with a loose threshold (28). On text / mostly
 *     white pages many wrong offsets score about the same, ties
 *     kept the first (= smallest) overlap, so a whole band of the
 *     previous frame was appended again.
 *  3. The new frame was drawn WHOLE over the old image at the
 *     overlap offset, so a sticky top/bottom bar inside the new
 *     frame overwrote real content in the middle of the long image
 *     and repeated there.
 * Fixes here: wider search window (+/- 40%), 320px-wide matching
 * with a coarse-to-fine search, a stricter match threshold, and
 * append-only drawing (only the part of the new frame below the
 * overlap is added, the overlapping part is never re-drawn).
 * Sticky bars are handled by CaptureService, which strips static
 * top/bottom rows from the content it stitches and re-adds them
 * once at the very top / bottom of the final image.
 */
public class Stitcher {

    public static class Result {
        public final Bitmap bitmap;
        public final int overlap;
        public final boolean matched;

        public Result(Bitmap bitmap, int overlap, boolean matched) {
            this.bitmap = bitmap;
            this.overlap = overlap;
            this.matched = matched;
        }
    }

    private static final int MATCH_WIDTH = 320;

    /** Chosen vertical overlap between a (above) and b (below). */
    public static class OverlapInfo {
        public final int overlap;
        public final boolean matched;
        // Set when the strong-band check stands behind this
        // overlap: either a strong band's best position IS the
        // incoming candidate (support), or the overlap is the
        // strong-band challenger that just won its arbitration.
        // The trimmed verification then demands its strict
        // (2.2x) ratio before overturning it with whole-overlap
        // statistics - on capture 2's f2->f3 those statistics
        // are majority-video poisoned and prefer the wrong
        // offset the strong band exists to overrule (v1.28).
        // Internal to findVerticalOverlap.
        public final boolean strongSupport;

        public OverlapInfo(int overlap, boolean matched) {
            this(overlap, matched, false);
        }

        public OverlapInfo(int overlap, boolean matched, boolean strongSupport) {
            this.overlap = overlap;
            this.matched = matched;
            this.strongSupport = strongSupport;
        }
    }

    /**
     * Find the vertical overlap between a and b without stitching.
     * CaptureService uses this first so it can back-fill content that
     * floating on-screen elements hid in the previous frame (using
     * this frame at the aligned position) before stitching.
     */
    // ---- Row-trimmed scoring (v1.27) --------------------------------
    // The whole-overlap statistics (plain mean, and even the median
    // of 8 bands) fail when MORE than half the overlap is dynamic:
    // on Hayes's real news page one join's overlap was majority GIF
    // ad + video, the true offset scored 28.6 (plain mean) and the
    // matcher fell back to the gesture estimate, losing 434px; the
    // next bad join was a confident wrong offset through a playing
    // short-drama ad. Scoring per ROW and averaging only the best
    // 55% of rows uses the static rows wherever they are - inside
    // otherwise poisoned bands too - and on the replayed real
    // frames the trimmed argmin is the true offset on every join.
    private static double trimmedScoreSmall(int[] grayA, int shA, int[] grayB, int sw,
                                            int overlapSmall, int topSkip) {
        int totalRows = overlapSmall - topSkip;
        if (totalRows < 8) return Double.MAX_VALUE;
        int startA = shA - overlapSmall + topSkip;
        if (startA < 0) return Double.MAX_VALUE;
        java.util.ArrayList<Double> rows = new java.util.ArrayList<>();
        for (int y = topSkip; y < overlapSmall; y += 2) {
            int yA = startA + (y - topSkip);
            if (yA < 0 || yA >= shA) continue;
            int rowA = yA * sw;
            int rowB = y * sw;
            long sum = 0;
            int count = 0;
            for (int x = 0; x < sw; x += 3) {
                int idxA = rowA + x;
                int idxB = rowB + x;
                if (idxA >= grayA.length || idxB >= grayB.length) break;
                sum += clipDiff(Math.abs(grayB[idxB] - grayA[idxA]));
                count++;
            }
            if (count > 0) rows.add((double) sum / count);
        }
        if (rows.size() < 4) return Double.MAX_VALUE;
        java.util.Collections.sort(rows);
        int keep = Math.max(1, (int) (rows.size() * 0.55));
        double acc = 0;
        for (int i = 0; i < keep; i++) acc += rows.get(i);
        return acc / keep;
    }

    private static double trimmedScoreFull(Bitmap a, Bitmap b, int overlap) {
        int w = a.getWidth();
        int hA = a.getHeight();
        if (overlap < 16 || overlap >= b.getHeight() || overlap >= hA) return Double.MAX_VALUE;
        java.util.ArrayList<Double> rows = new java.util.ArrayList<>();
        for (int y = 0; y < overlap; y += 3) {
            int rowA = (hA - overlap + y) * w;
            int rowB = y * w;
            long sum = 0;
            int count = 0;
            for (int x = 0; x < w; x += 6) {
                sum += clipDiff(Math.abs(grayOf(b.getPixel(x, y)) - grayOf(a.getPixel(x, hA - overlap + y))));
                count++;
            }
            if (count > 0) rows.add((double) sum / count);
        }
        if (rows.size() < 4) return Double.MAX_VALUE;
        java.util.Collections.sort(rows);
        int keep = Math.max(1, (int) (rows.size() * 0.55));
        double acc = 0;
        for (int i = 0; i < keep; i++) acc += rows.get(i);
        return acc / keep;
    }

    // Median of 8 band scores at full resolution (fixed band
    // count, unlike the small-space scoreOverlap whose band count
    // shrinks with the overlap and gets too coarse to compare a
    // small challenger against a larger candidate).
    private static double medianBandFull(Bitmap a, Bitmap b, int overlap) {
        int w = a.getWidth();
        int hA = a.getHeight();
        if (overlap < 16 || overlap >= b.getHeight() || overlap >= hA) return Double.MAX_VALUE;
        int bands = 8;
        int bandRows = Math.max(1, overlap / bands);
        double[] bs = new double[bands];
        for (int k = 0; k < bands; k++) {
            int yFrom = k * bandRows;
            int yTo = (k == bands - 1) ? overlap : Math.min(overlap, yFrom + bandRows);
            long sum = 0;
            int count = 0;
            for (int y = yFrom; y < yTo; y += 2) {
                for (int x = 0; x < w; x += 4) {
                    sum += clipDiff(Math.abs(grayOf(b.getPixel(x, y))
                            - grayOf(a.getPixel(x, hA - overlap + y))));
                    count++;
                }
            }
            bs[k] = count == 0 ? Double.MAX_VALUE : (double) sum / count;
        }
        java.util.Arrays.sort(bs);
        return bs[bands / 2];
    }

    private static int grayOf(int c) {
        return (int) (0.299 * ((c >> 16) & 0xFF) + 0.587 * ((c >> 8) & 0xFF) + 0.114 * (c & 0xFF));
    }

    // Full-res row-trimmed polish: the best overlap within
    // +/-16px of the given one under trimmedScoreFull.
    private static int polishTrimmedFull(Bitmap a, Bitmap b, int overlap, int hB) {
        int best = overlap;
        double bestScore = Double.MAX_VALUE;
        for (int o = Math.max(16, overlap - 16); o <= Math.min(hB - 8, overlap + 16); o += 2) {
            double sc = trimmedScoreFull(a, b, o);
            if (sc < bestScore) { bestScore = sc; best = o; }
        }
        return best;
    }

    private static double bandDetail(int[] grayB, int sw, int bStart, int rows) {
        double sum = 0;
        int n = 0;
        for (int y = bStart; y < bStart + rows; y += 2) {
            int row = y * sw;
            for (int x = 0; x < sw - 4; x += 4) {
                sum += Math.abs(grayB[row + x + 4] - grayB[row + x]);
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    // Strong-band check (v1.28): one DETAILED band whose trimmed
    // score is excellent (< 6) is decisive evidence - on Hayes's
    // real capture a two-line title inside a band that was 2/3
    // playing video voted the true offset at score 1.0 while every
    // whole-overlap statistic (and the >=3-vote consensus quorum,
    // starved because a large step leaves few bands fully inside
    // the overlap) produced a confident wrong offset ~380px away.
    // Applied to MATCHED candidates only (unmatched fallbacks are
    // the trimmed stage's job): replaces the candidate when the
    // best strong vote disagrees grossly (> 24 small rows) and no
    // strong vote supports the candidate (within 8 small rows).
    // The trimmed stage runs after this and can still override
    // the replacement when its own evidence is strong.
    private static OverlapInfo strongBandCheck(Bitmap a, Bitmap b, int[] grayA, int shA,
                                               int[] grayB, int shB, int sw, int w, int hB,
                                               int loS, int hiS, int topSkip, int expected,
                                               int candOverlap, boolean candMatched) {
        if (!candMatched) return new OverlapInfo(candOverlap, false);
        int candS = (int) Math.round(candOverlap * (double) sw / w);
        int regionStart = Math.max(topSkip, (int) (shB * 0.10));
        int regionEnd = (int) (shB * 0.75);
        int bandCount = 6;
        int bandH = Math.max(6, (regionEnd - regionStart) / bandCount);
        int bestStrong = -1;
        double bestStrongScore = Double.MAX_VALUE;
        int bestStrongB0 = -1;
        int bestStrongRows = 0;
        boolean supported = false;
        for (int k = 0; k < bandCount; k++) {
            int b0 = regionStart + k * bandH;
            int b1 = Math.min(regionEnd, b0 + bandH);
            if (b1 - b0 < 4) continue;
            int pLo = Math.max(0, shA - hiS + b0);
            int pHi = Math.min(shA - (b1 - b0), shA - loS + b0);
            if (pHi < pLo) continue;
            int bestP = -1;
            double bestScore = Double.MAX_VALUE;
            for (int p = pLo; p <= pHi; p += 2) {
                double sc = trimmedBandScore(grayA, shA, grayB, sw, p, b0, b1 - b0);
                if (sc < bestScore) { bestScore = sc; bestP = p; }
            }
            if (bestP < 0) continue;
            for (int p = Math.max(pLo, bestP - 2); p <= Math.min(pHi, bestP + 2); p++) {
                double sc = trimmedBandScore(grayA, shA, grayB, sw, p, b0, b1 - b0);
                if (sc < bestScore) { bestScore = sc; bestP = p; }
            }
            if (bestScore >= 6.0) continue;
            int vote = shA + b0 - bestP;
            // Support first: a band whose best position IS the
            // candidate supports it, full stop - even if the band
            // is also self-similar elsewhere. (An earlier ordering
            // let the ambiguity guard below skip exactly these
            // bands, leaving correct candidates "unsupported" and
            // exposed to a false challenger.)
            if (Math.abs(vote - candS) <= 8) { supported = true; continue; }
            if (bandDetail(grayB, sw, b0, b1 - b0) <= 2.5) continue;
            // Ambiguity guard for challengers: if the SAME band
            // also scores excellently at the candidate's own
            // position, its content is self-similar (repetitive
            // cards - the bench's newsfeed alias band scores < 6
            // at both the true offset and one card period away)
            // and its vote decides nothing. When the candidate
            // position is not comparable for this band (the band
            // straddles the candidate's overlap boundary), the
            // vote stands: the band's content demonstrably already
            // exists in the canvas, which is exactly the
            // duplication case this check exists for.
            int pCand = shA + b0 - candS;
            if (pCand >= pLo && pCand <= pHi) {
                double atCand = trimmedBandScore(grayA, shA, grayB, sw, pCand, b0, b1 - b0);
                if (atCand < 6.0) continue;
            }
            if (bestScore < bestStrongScore) {
                bestStrongScore = bestScore;
                bestStrong = vote;
                bestStrongB0 = b0;
                bestStrongRows = b1 - b0;
            }
        }
        if (bestStrong < 0 || supported) return new OverlapInfo(candOverlap, true, supported);
        if (Math.abs(bestStrong - candS) <= 24) return new OverlapInfo(candOverlap, true);
        int full = (int) Math.round(bestStrong * (double) w / sw);
        // The challenger must respect the same scroll plausibility
        // as every other stage: on the bench's layout-shift
        // scenario a band from the shifted segment voted an offset
        // implying a scroll ~2x the gesture - a reflow/alias
        // artifact, not evidence about this join.
        if (!scrollPlausible(hB, expected, full)) return new OverlapInfo(candOverlap, true);
        // Final arbiter, measured on the two real failure modes:
        // the challenger must beat the candidate on the DETAIL
        // rows of the overlap. On Hayes's capture the wrong
        // candidate misaligns the title rows (detail score high)
        // while the strong vote aligns them; on the bench's
        // layout-shift scenario a synthetic band scored 0.00 at
        // an offset 90px from the truth, but there the candidate
        // aligns the detail rows and the challenger does not.
        // Repetitive text (another card's title) aligns equally
        // well at both offsets and fails the ratio - by design.
        // Sub-band confirmation: the winning band's two halves
        // must EACH align well at the challenger offset (score
        // at the fixed offset, no search). A single lucky block
        // can win a whole band; two independent halves of it
        // aligning at the same offset is the band's own internal
        // corroboration.
        {
            int pChal = shA + bestStrongB0 - bestStrong;
            int half = bestStrongRows / 2;
            double topHalf = trimmedBandScore(grayA, shA, grayB, sw, pChal, bestStrongB0, half);
            double botHalf = trimmedBandScore(grayA, shA, grayB, sw,
                    pChal + half, bestStrongB0 + half, bestStrongRows - half);
            if (topHalf >= 8.0 || botHalf >= 8.0) return new OverlapInfo(candOverlap, true);
        }
        // Detail-trimmed arbiter as a contradiction veto only:
        // the challenger must not be MUCH worse than the
        // candidate on the overlap's detail rows. (As a strict
        // ratio it also vetoed the true challenger on Hayes's
        // capture, where the overlap is majority playing video
        // and even the true offset's detail score is mediocre;
        // as a veto it still stops synthetic challengers whose
        // detail rows plainly belong to the candidate.)
        double detCand = detailTrimmedScoreFull(a, b, candOverlap);
        double detChal = detailTrimmedScoreFull(a, b, full);
        if (detChal == Double.MAX_VALUE) return new OverlapInfo(candOverlap, true);
        if (detCand == Double.MAX_VALUE) {
            // The candidate's overlap holds almost no detailed
            // rows to judge by; a challenger whose detail rows
            // align near-perfectly is still decisive.
            if (detChal >= 6.0) return new OverlapInfo(candOverlap, true);
        } else if (detChal > 1.5 * detCand) {
            return new OverlapInfo(candOverlap, true);
        }
        full = polishTrimmedFull(a, b, full, hB);
        return new OverlapInfo(full, true, true);
    }

    // Detail-restricted row-trimmed score (v1.28): like
    // trimmedScoreFull but only over rows whose own horizontal
    // detail is at least 6 (text / sharp photo rows), taking the
    // best 70% of those. Blank and smooth rows - the ones that
    // made every whole-overlap statistic prefer a wrong offset on
    // Hayes's real capture - are excluded entirely; dynamic
    // textured rows score badly at BOTH offsets being compared,
    // so the static detailed rows decide the comparison. Returns
    // MAX_VALUE when the overlap has too few detailed rows to
    // judge (abstain).
    private static double detailTrimmedScoreFull(Bitmap a, Bitmap b, int overlap) {
        int w = b.getWidth();
        int hA = a.getHeight();
        if (overlap < 16 || overlap >= b.getHeight() || overlap >= hA) return Double.MAX_VALUE;
        int[] hist = new int[49];
        int counted = 0;
        for (int y = 0; y < overlap; y++) {
            int yA = hA - overlap + y;
            double det = 0;
            int dn = 0;
            for (int x = 0; x < w - 6; x += 6) {
                det += Math.abs(grayOf(b.getPixel(x + 6, y)) - grayOf(b.getPixel(x, y)));
                dn++;
            }
            if (dn == 0 || det / dn < 6.0) continue;
            double rowSum = 0;
            int n = 0;
            for (int x = 0; x < w; x += 6) {
                rowSum += clipDiff(Math.abs(grayOf(b.getPixel(x, y)) - grayOf(a.getPixel(x, yA))));
                n++;
            }
            if (n == 0) continue;
            int bucket = (int) (rowSum / n);
            if (bucket > 48) bucket = 48;
            hist[bucket]++;
            counted++;
        }
        if (counted < 6) return Double.MAX_VALUE;
        int keep = Math.max(1, (int) Math.ceil(counted * 0.55));
        long acc = 0;
        int taken = 0;
        for (int bIdx = 0; bIdx <= 48 && taken < keep; bIdx++) {
            int take = Math.min(hist[bIdx], keep - taken);
            acc += (long) take * bIdx;
            taken += take;
        }
        return (double) acc / keep;
    }

    // Verifies a candidate overlap with row-trimmed evidence and,
    // when the trimmed search over a widened plausibility window
    // finds a clearly better alignment, replaces it:
    //  - matched candidate: replaced only when the trimmed argmin
    //    is very good (< 6) AND the candidate's trimmed score is
    //    more than 1.5x worse AND they differ by > 6 small rows
    //    (a confident wrong offset through animated content).
    //    (The ratio was 2.2x through v1.32, calibrated on one
    //    capture whose scores separated 4x; on capture 5's
    //    white, video-heavy feed the true and alias trimmed
    //    scores separate only 1.7x - 2.76 vs 4.71 - because the
    //    best-55%-of-rows statistic compresses both toward the
    //    white margins. The sharp-basin, detail-row cross-check
    //    and plausibility gates carry the discrimination the
    //    ratio alone cannot.)
    //  - unmatched candidate (gesture-estimate fallback): replaced
    //    when the trimmed argmin is decent (<= 22) and at least
    //    15% better than the fallback's trimmed score (a majority-
    //    dynamic overlap the other statistics could not certify).
    // A full-res trimmed polish (+/-16px) pins the replacement
    // before the usual detail refinement runs on it.
    private static OverlapInfo trimmedCheck(Bitmap a, Bitmap b, int[] grayA, int shA,
                                            int[] grayB, int shB, int sw, int w, int hB,
                                            int expected, int topSkip,
                                            int candOverlap, boolean candMatched,
                                            boolean candProtected) {
        int candS = (int) Math.round(candOverlap * (double) sw / w);
        candS = Math.max(topSkip + 8, Math.min(shB - 8, candS));
        int expScrollS = shB - (int) Math.round(expected * (double) sw / w);
        int oLo, oHi;
        if (expScrollS >= 16) {
            int maxScroll = (int) Math.round(expScrollS * 1.9);
            int minScroll = Math.max(8, (int) Math.round(expScrollS * 0.35));
            oLo = shB - maxScroll;
            oHi = shB - minScroll;
        } else {
            oLo = topSkip + 8;
            oHi = shB - 8;
        }
        oLo = Math.max(topSkip + 24, oLo);
        oHi = Math.min(shB - 8, oHi);
        if (oHi - oLo < 12) return new OverlapInfo(candOverlap, candMatched);
        int argS = -1;
        double argTrim = Double.MAX_VALUE;
        // Step 1, not 2: at this scale (1 small row = ~4 full-res
        // px) a true alignment's basin can be a single small row
        // wide - a step-2 grid landed on both shoulders of the
        // capture-5 f7->f8 basin (272 half-res, trimmed 2.76)
        // and crowned a 4.71 alias one row off the truth's
        // neighbours. The window is a few hundred rows; the
        // scan is not the place to save the factor of two.
        for (int o = oLo; o <= oHi; o++) {
            double s = trimmedScoreSmall(grayA, shA, grayB, sw, o, topSkip);
            if (s < argTrim) { argTrim = s; argS = o; }
        }
        if (argS < 0) return new OverlapInfo(candOverlap, candMatched);
        for (int o = Math.max(oLo, argS - 2); o <= Math.min(oHi, argS + 2); o++) {
            double s = trimmedScoreSmall(grayA, shA, grayB, sw, o, topSkip);
            if (s < argTrim) { argTrim = s; argS = o; }
        }
        double candTrim = trimmedScoreSmall(grayA, shA, grayB, sw, candS, topSkip);
        // Cross-statistic check: the challenger must also hold up
        // under the median-of-bands statistic, not just on its best
        // rows. A blank-block alias aligns a minority of rows
        // perfectly (trimmed 0.00) while most of its bands misalign
        // (median-band 12.4 vs the true offset's 1.0 - bench
        // ProbeLarge3); on the real wrong-offset join the true
        // challenger wins BOTH statistics (trimmed 1.8 vs 7.4,
        // median-band ~13 vs ~19).
        double medCand = medianBandFull(a, b, candOverlap);
        double medArg = medianBandFull(a, b, (int) Math.round(argS * (double) w / sw));
        // Cross-statistic check, v1.33: the challenger must not be
        // contradicted by the DETAIL rows. The median-of-bands
        // statistic used here through v1.32 fails exactly when
        // the true overlap is majority dynamic - on capture 5's
        // f7->f8 the true offset's median band is 30.2 against
        // the alias's 16.3, because most bands are playing
        // video - while its detail rows (static text / sharp
        // rows only, row-trimmed) separate cleanly: 6.8 at the
        // truth vs 14.1 at the alias. The detail statistic is
        // therefore the arbiter, in the same veto form the
        // strong-band check uses: veto only when the challenger
        // is much worse (> 1.5x) on detail rows. Capture 2's
        // f2->f3 challenger survives it (21.8 vs the wrong
        // candidate's 15.7, inside 1.5x), and the blank-block
        // aliases the median-band check was added for are still
        // vetoed: their detail rows belong to the candidate.
        // When the challenger has too few detailed rows to
        // judge (MAX), fall back to the median-band check.
        double detCand = detailTrimmedScoreFull(a, b, candOverlap);
        double detArg = detailTrimmedScoreFull(a, b,
                (int) Math.round(argS * (double) w / sw));
        boolean crossOk;
        if (detArg == Double.MAX_VALUE) {
            crossOk = medArg <= medCand + 1.0;
        } else if (detCand == Double.MAX_VALUE) {
            crossOk = detArg < 6.0;
        } else {
            crossOk = detArg <= 1.5 * detCand;
        }
        boolean replace;
        if (candMatched) {
            // Overriding a scored match demands a SHARP basin at
            // the challenger: in smooth content (big photos, blank
            // blocks) many offsets score < 6 on their best rows and
            // the argmin is a coin flip - the bench's large-step and
            // side-button scenarios regressed on exactly that. On
            // the real wrong-offset join the basin is sharp (text
            // rows): trimmed +-12 small rows around the argmin is
            // ~2.9x the minimum.
            boolean sharp = false;
            if (argS - 12 >= oLo && argS + 12 <= oHi) {
                double tL = trimmedScoreSmall(grayA, shA, grayB, sw, argS - 12, topSkip);
                double tR = trimmedScoreSmall(grayA, shA, grayB, sw, argS + 12, topSkip);
                double tMin = Math.min(tL, tR);
                sharp = tMin > 1.8 * argTrim && tMin > argTrim + 1.5;
            }
            // candTrim >= 1.0: when both trimmed scores are near
            // zero the ratio between them is noise, not evidence -
            // never override an essentially perfect candidate.
            // A strong-band-backed candidate (candProtected)
            // carries sharp local evidence, so overturning it
            // demands the original strict ratio (2.2x); an
            // unbacked candidate falls at 1.5x. Immunity is NOT
            // granted: on capture 1's f4->f5 a single band
            // scored 0.43 at a wrong offset (a story the feed
            // repeats, matched against the longer canvas) and
            // only the whole-overlap trimmed evidence - 3.1x
            // better at the truth - sees through it.
            double ratioGate = candProtected ? 2.2 : 1.5;
            replace = sharp && crossOk && candTrim >= 1.0 && argTrim < 6.0
                    && candTrim > ratioGate * argTrim
                    && Math.abs(candS - argS) > 6;
        } else {
            // Distrust the gesture fallback only when the trimmed
            // evidence says the fallback itself is BAD (> 18): in
            // smooth content every offset scores low on its best
            // rows, the fallback included, and a flat-curve argmin
            // is not evidence (the bench's large-step scenario
            // regressed on exactly that). On the real majority-
            // dynamic join the fallback scored 24.4 vs argmin 19.6.
            replace = crossOk && candTrim > 18.0 && argTrim <= 22.0
                    && argTrim <= 0.85 * candTrim;
        }
        if (!replace) return new OverlapInfo(candOverlap, candMatched);
        int argFull = (int) Math.round(argS * (double) w / sw);
        return new OverlapInfo(polishTrimmedFull(a, b, argFull, hB), true);
    }

    public static OverlapInfo findVerticalOverlap(Bitmap a, Bitmap b, double expectedOverlapRatio, double topSkipRatio) {
        if (a == null || b == null) return new OverlapInfo(0, false);
        Bitmap bb = b;
        boolean scaledCopy = false;
        if (bb.getWidth() != a.getWidth()) {
            bb = Bitmap.createScaledBitmap(b, a.getWidth(),
                    (int) Math.round((double) b.getHeight() * a.getWidth() / b.getWidth()), true);
            scaledCopy = true;
        }
        int w = a.getWidth();
        int hB = bb.getHeight();
        int expected = (int) Math.round(hB * expectedOverlapRatio);
        // Wider window than the old +/-25%: real scroll != gesture distance.
        int lo = Math.max((int) (hB * 0.05), expected - (int) (hB * 0.40));
        int hi = Math.min((int) (hB * 0.95), expected + (int) (hB * 0.40));
        if (hi <= lo) { lo = (int) (hB * 0.1); hi = (int) (hB * 0.9); }

        int[] grayA = toGraySmall(a, MATCH_WIDTH);
        int[] grayB = toGraySmall(bb, MATCH_WIDTH);
        int sw = MATCH_WIDTH;
        int shA = (int) Math.round((double) a.getHeight() * sw / a.getWidth());
        int shB = (int) Math.round((double) bb.getHeight() * sw / bb.getWidth());
        int topSkip = (int) (shB * topSkipRatio);

        int loS = Math.max(topSkip + 4, (int) Math.round(lo * (double) sw / w));
        int hiS = Math.min(shB - 2, (int) Math.round(hi * (double) sw / w));
        int bestOverlapSmall = (int) Math.round(expected * (double) sw / w);
        double bestScore = Double.MAX_VALUE;

        // ---- Consensus bands (v1.15) ----------------------------------
        // Split the new frame into horizontal rigid bands, find each
        // band's offset independently, and let the bands vote. A band
        // containing motion (video, GIF, loading spinner) or a
        // floating element produces a wild offset or a bad score and
        // is out-voted / dropped, instead of dragging the whole-frame
        // match with it the way the single global search below can be.
        // Bands cover 10%..75% of the frame height: the top strip can
        // hold a sticky header, the bottom strip a floating dock.
        if (hiS > loS) {
            int bandRegionStart = Math.max(topSkip, (int) (shB * 0.10));
            int bandRegionEnd = (int) (shB * 0.75);
            int bandCount = 6;
            int bandH = Math.max(6, (bandRegionEnd - bandRegionStart) / bandCount);
            java.util.List<Integer> votes = new java.util.ArrayList<>();
            for (int k = 0; k < bandCount; k++) {
                int b0 = bandRegionStart + k * bandH;
                int b1 = Math.min(bandRegionEnd, b0 + bandH);
                if (b1 - b0 < 4) continue;
                // A-row of the band top for overlap o is shA - o + b0,
                // so o in [loS, hiS] means the A-row is in this range:
                int pLo = Math.max(0, shA - hiS + b0);
                int pHi = Math.min(shA - (b1 - b0), shA - loS + b0);
                if (pHi < pLo) continue;
                int bestP = -1;
                double bestBandScore = Double.MAX_VALUE;
                for (int p = pLo; p <= pHi; p += 2) {
                    double s = scoreBand(grayA, shA, grayB, sw, p, b0, b1 - b0);
                    if (s < bestBandScore) { bestBandScore = s; bestP = p; }
                }
                if (bestP >= 0) {
                    int fLo = Math.max(pLo, bestP - 2);
                    int fHi = Math.min(pHi, bestP + 2);
                    for (int p = fLo; p <= fHi; p++) {
                        double s = scoreBand(grayA, shA, grayB, sw, p, b0, b1 - b0);
                        if (s < bestBandScore) { bestBandScore = s; bestP = p; }
                    }
                }
                // Only trustworthy bands vote: identical static content
                // scores near 0-8; animated / re-laid-out bands score
                // high and are excluded here, not averaged in.
                if (bestP >= 0 && bestBandScore < 18.0) {
                    votes.add(shA + b0 - bestP);
                }
            }
            if (votes.size() >= 3) {
                java.util.Collections.sort(votes);
                int median = votes.get(votes.size() / 2);
                int agreeing = 0;
                for (int v : votes) if (Math.abs(v - median) <= 3) agreeing++;
                if (agreeing >= 3 && agreeing * 2 >= votes.size()) {
                    // Mean of the agreeing votes, not the median: one
                    // small row is ~2-4 original px, and the median of
                    // an even split (e.g. votes 246/247 for a true
                    // 246.7) rounds to a full small row of error -
                    // a systematic +/-1..2px bias the old code had.
                    long voteSum = 0;
                    int voteCount = 0;
                    for (int v : votes) {
                        if (Math.abs(v - median) <= 3) { voteSum += v; voteCount++; }
                    }
                    double consensusSmall = voteCount > 0
                            ? (double) voteSum / voteCount : median;
                    int consensusOverlap = (int) Math.round(consensusSmall * w / sw);
                    if (consensusOverlap < 0) consensusOverlap = 0;
                    if (consensusOverlap >= hB) consensusOverlap = hB - 1;
                    // Plausibility gate (see below): a consensus that
                    // implies a scroll far outside what the gesture can
                    // produce is an alias on repetitive content - do
                    // not trust it, fall through to the global search.
                    if (scrollPlausible(hB, expected, consensusOverlap)) {
                        OverlapInfo v = trimmedCheck(a, bb, grayA, shA, grayB, shB,
                                sw, w, hB, expected, topSkip, consensusOverlap, true, false);
                        consensusOverlap = refineVerticalOverlap(a, bb, v.overlap);
                        if (scaledCopy) bb.recycle();
                        return new OverlapInfo(consensusOverlap, true);
                    }
                }
            }
        }
        // ---- Global search (fallback when bands cannot agree) --------
        OverlapInfo global = globalOverlap(grayA, shA, grayB, shB, sw, w, hB,
                loS, hiS, topSkip, expected, Double.MAX_VALUE);
        int overlap = global.overlap;
        boolean matched = global.matched;
        // ---- Scroll-amount plausibility gate --------------------------
        // The overlap implies a scroll distance (hB - overlap). The
        // gesture that produced this frame bounds what the scroll can
        // physically be: a slow 900ms drag cannot move the page by
        // less than ~45% or more than ~165% of its own length (fling
        // and container elasticity included). Repetitive feeds break
        // the plain argmin here: on the test bench a news-feed join
        // chose an overlap exactly one card period (430px) too large
        // - an implied scroll of only 143px after a 480px swipe -
        // silently dropping 430px of content, and the same mechanism
        // sliced text lines in the real browser test. So: if the best
        // match implies an implausible scroll, re-search INSIDE the
        // plausible window and prefer that match when it is itself
        // confident. If nothing in the window matches confidently,
        // keep the original (strong evidence of a genuinely odd
        // scroll, e.g. the page nearly exhausted, beats a weak guess).
        if (matched && !scrollPlausible(hB, expected, overlap)) {
            int expScroll = hB - expected;
            int minOvPx = hB - (int) Math.round(expScroll * 1.65);
            int maxOvPx = hB - (int) Math.round(expScroll * 0.45);
            int rLoS = Math.max(loS, (int) Math.round(minOvPx * (double) sw / w));
            int rHiS = Math.min(hiS, (int) Math.round(maxOvPx * (double) sw / w));
            if (rHiS > rLoS) {
                OverlapInfo alt = globalOverlap(grayA, shA, grayB, shB, sw, w, hB,
                        rLoS, rHiS, topSkip, expected, Double.MAX_VALUE);
                if (alt.matched) {
                    overlap = alt.overlap;
                    matched = true;
                }
            }
        }
        // ---- Strip rescue (when the whole-overlap match failed) ------
        // If one dynamic element is TALLER than most of the overlap (a
        // full-width video card in a feed), every whole-overlap
        // statistic is poisoned and the caller would fall back to the
        // raw gesture estimate, whose error is the fling variance -
        // tens of px of lost or duplicated content. But the overlap's
        // top strip (above the video) or bottom strip (below it) is
        // usually static. Search the plausible window scoring only
        // the top 30% strip, then only the bottom 30% strip; a strip
        // that aligns cleanly (< 14 clipped mean) is decisive, and
        // the plausibility window keeps repetitive-content aliases
        // (a full card period away) out of the search.
        if (!matched) {
            OverlapInfo rescue = stripRescue(grayA, shA, grayB, shB, sw, w, hB,
                    loS, hiS, topSkip, expected);
            if (rescue != null) {
                overlap = rescue.overlap;
                matched = true;
            }
        }
        {
            OverlapInfo sb = strongBandCheck(a, bb, grayA, shA, grayB, shB,
                    sw, w, hB, loS, hiS, topSkip, expected, overlap, matched);
            overlap = sb.overlap;
            matched = matched || sb.matched;
            OverlapInfo v = trimmedCheck(a, bb, grayA, shA, grayB, shB,
                    sw, w, hB, expected, topSkip, overlap, matched, sb.strongSupport);
            overlap = v.overlap;
            matched = matched || v.matched;
        }
        if (matched) {
            overlap = refineVerticalOverlap(a, bb, overlap);
        }
        if (scaledCopy) bb.recycle();
        return new OverlapInfo(overlap, matched);
    }

    /** Prefix/suffix strip matching inside the plausible scroll window, or null. */
    private static OverlapInfo stripRescue(int[] grayA, int shA, int[] grayB, int shB,
                                           int sw, int w, int hB, int loS, int hiS,
                                           int topSkip, int expected) {
        int expScroll = hB - expected;
        if (expScroll < 40) return null;
        int minOvPx = hB - (int) Math.round(expScroll * 1.65);
        int maxOvPx = hB - (int) Math.round(expScroll * 0.45);
        int rLoS = Math.max(loS, (int) Math.round(minOvPx * (double) sw / w));
        int rHiS = Math.min(hiS, (int) Math.round(maxOvPx * (double) sw / w));
        if (rHiS <= rLoS) return null;
        OverlapInfo best = null;
        double bestScore = 14.0;
        for (int mode = 0; mode < 2; mode++) {
            boolean prefix = mode == 0;
            int bestO = -1;
            double bs = Double.MAX_VALUE;
            for (int o = rLoS; o <= rHiS; o += 2) {
                double s = stripScoreAt(grayA, shA, grayB, sw, o, topSkip, prefix);
                if (s < bs) { bs = s; bestO = o; }
            }
            if (bestO < 0) continue;
            int fLo = Math.max(rLoS, bestO - 2);
            int fHi = Math.min(rHiS, bestO + 2);
            for (int o = fLo; o <= fHi; o++) {
                double s = stripScoreAt(grayA, shA, grayB, sw, o, topSkip, prefix);
                if (s < bs) { bs = s; bestO = o; }
            }
            if (bs < bestScore) {
                bestScore = bs;
                int ov = (int) Math.round((double) bestO * w / sw);
                if (ov < 0) ov = 0;
                if (ov >= hB) ov = hB - 1;
                best = new OverlapInfo(ov, true);
            }
        }
        return best;
    }

    /** Clipped-mean score of only the top (prefix) or bottom (suffix) 30% strip of the overlap at candidate o. */
    private static double stripScoreAt(int[] grayA, int shA, int[] grayB, int sw,
                                       int o, int topSkip, boolean prefix) {
        int avail = o - topSkip;
        if (avail < 12) return Double.MAX_VALUE;
        int len = Math.max(10, (int) (avail * 0.30));
        if (len > avail) len = avail;
        int bFrom = prefix ? topSkip : o - len;
        long sum = 0;
        long count = 0;
        for (int y = bFrom; y < bFrom + len; y++) {
            int yA = shA - o + y;
            if (yA < 0 || yA >= shA) continue;
            int rowB = y * sw;
            int rowA = yA * sw;
            for (int x = 0; x < sw; x += 2) {
                int idxB = rowB + x;
                int idxA = rowA + x;
                if (idxB >= grayB.length || idxA >= grayA.length) break;
                sum += clipDiff(Math.abs(grayB[idxB] - grayA[idxA]));
                count++;
            }
        }
        return count == 0 ? Double.MAX_VALUE : (double) sum / count;
    }

    /** True if the scroll implied by an overlap is within [0.45x, 1.65x] of the expected scroll. */
    private static boolean scrollPlausible(int hB, int expectedOverlap, int overlap) {
        int expScroll = hB - expectedOverlap;
        if (expScroll < 40) return true; // no meaningful gesture estimate
        int scroll = hB - overlap;
        return scroll >= expScroll * 0.45 && scroll <= expScroll * 1.65;
    }

    /** Coarse-to-fine global overlap search over a small-unit range. */
    private static OverlapInfo globalOverlap(int[] grayA, int shA, int[] grayB, int shB,
                                             int sw, int w, int hB, int loS, int hiS,
                                             int topSkip, int expected, double initScore) {
        int bestOverlapSmall = (int) Math.round(expected * (double) sw / w);
        double bestScore = initScore;
        if (hiS > loS) {
            // Coarse pass: every 4 small rows.
            for (int o = loS; o <= hiS; o += 4) {
                double s = scoreOverlap(grayA, shA, grayB, shB, sw, o, topSkip);
                if (s < bestScore) { bestScore = s; bestOverlapSmall = o; }
            }
            // Fine pass: every small row around the coarse winner.
            int fLo = Math.max(loS, bestOverlapSmall - 4);
            int fHi = Math.min(hiS, bestOverlapSmall + 4);
            for (int o = fLo; o <= fHi; o++) {
                double s = scoreOverlap(grayA, shA, grayB, shB, sw, o, topSkip);
                if (s < bestScore) { bestScore = s; bestOverlapSmall = o; }
            }
        }
        int bestOverlap = (int) Math.round((double) bestOverlapSmall * w / sw);
        // Identical screenshot content scores near 0-8 on this scale;
        // 28 (old) accepted clearly wrong offsets. 20 is the new bound.
        boolean matched = bestScore < 20.0;
        int overlap = matched ? bestOverlap : expected;
        if (overlap < 0) overlap = 0;
        if (overlap >= hB) overlap = hB - 1;
        return new OverlapInfo(overlap, matched);
    }

    /**
     * Full-resolution refinement of an overlap found on the 320px-wide
     * small image. One small row is ~4 original pixels, so even a
     * perfect small-scale match can leave a seam off by up to ~4px,
     * and the band-consensus median can be a few px off as well -
     * clearly visible when a seam cuts through a line of text.
     * This takes a 48px-tall strip of b (two candidate positions,
     * the better-scoring one wins), and searches its exact position
     * in a at native resolution over +/-8px. If the strip does not
     * score well at any offset (dynamic / re-laid-out content), the
     * overlap is returned unchanged.
     */
    private static int refineVerticalOverlap(Bitmap a, Bitmap b, int overlap) {
        int w = a.getWidth();
        int hA = a.getHeight();
        int hB = b.getHeight();
        int stripH = 48;
        // Candidate strips inside the overlap region. A strip is only
        // useful if it has DETAIL (vertical gradient): a blank strip
        // scores 0 at every delta and "confirms" even a wrong offset
        // (this was the cause of persistent +/-1px overlaps: the old
        // selection preferred the lowest score at delta 0, which blank
        // strips always win). Among detailed strips, take the one
        // whose best delta scores lowest.
        int[] candY = {
                (int) (overlap * 0.20),
                (int) (overlap * 0.45),
                (int) (overlap * 0.70),
        };
        int bestDelta = 0;
        double bestStripScore = Double.MAX_VALUE;
        boolean found = false;
        for (int yB : candY) {
            if (yB + stripH > overlap) yB = overlap - stripH;
            if (yB < 0) continue;
            int yA = hA - overlap + yB;
            if (yA - 8 < 0 || yA + stripH + 8 > hA) continue;
            int[] grayA = grayRegion(a, yA - 8, stripH + 16);
            int[] grayB = grayRegion(b, yB, stripH);
            if (grayA == null || grayB == null) continue;
            if (stripDetail(grayB, w, stripH) < 2.0) continue;
            double scoreZero = stripScore(grayA, grayB, w, stripH, 0);
            int candBestDelta = 0;
            double candBest = scoreZero;
            for (int d = -8; d <= 8; d++) {
                if (d == 0) continue;
                double s = stripScore(grayA, grayB, w, stripH, d);
                if (s < candBest - 0.01 || (Math.abs(s - candBest) <= 0.01 && Math.abs(d) < Math.abs(candBestDelta))) {
                    candBest = s;
                    candBestDelta = d;
                }
            }
            if (!found || candBest < bestStripScore) {
                found = true;
                bestStripScore = candBest;
                bestDelta = candBestDelta;
            }
        }
        if (!found || bestStripScore > 25.0) {
            return overlap;
        }
        int refined = overlap - bestDelta;
        if (refined < Math.max(0, overlap - 16)) refined = Math.max(0, overlap - 16);
        if (refined > Math.min(hB - 1, overlap + 16)) refined = Math.min(hB - 1, overlap + 16);
        return refined;
    }

    /** Mean vertical gradient of a strip: ~0 for blank areas, high for text/edges. */
    private static double stripDetail(int[] gray, int w, int rows) {
        long sum = 0;
        int count = 0;
        for (int y = 1; y < rows; y++) {
            int row = y * w;
            int prev = (y - 1) * w;
            for (int x = 0; x < w; x += 4) {
                sum += Math.abs(gray[row + x] - gray[prev + x]);
                count++;
            }
        }
        return count == 0 ? 0 : (double) sum / count;
    }

    /** Grayscale of a full-width horizontal region, or null on failure. */
    private static int[] grayRegion(Bitmap bmp, int y, int rows) {
        int w = bmp.getWidth();
        if (y < 0 || rows <= 0 || y + rows > bmp.getHeight()) return null;
        int[] px = new int[w * rows];
        try {
            bmp.getPixels(px, 0, w, 0, y, w, rows);
        } catch (Exception e) {
            return null;
        }
        int[] gray = new int[px.length];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            gray[i] = (((p >> 16) & 0xff) * 30 + ((p >> 8) & 0xff) * 59 + (p & 0xff) * 11) / 100;
        }
        return gray;
    }

    /** Score b's strip against a's strip shifted by delta rows (a strip has 8px headroom on each side). */
    private static double stripScore(int[] grayA, int[] grayB, int w, int stripH, int delta) {
        long sum = 0;
        long count = 0;
        int aOffsetRows = 8 + delta;
        for (int y = 0; y < stripH; y++) {
            int rowA = (aOffsetRows + y) * w;
            int rowB = y * w;
            for (int x = 0; x < w; x += 3) {
                int idxA = rowA + x;
                int idxB = rowB + x;
                if (idxA >= grayA.length || idxB >= grayB.length) break;
                sum += clipDiff(Math.abs(grayB[idxB] - grayA[idxA]));
                count++;
            }
        }
        return count == 0 ? Double.MAX_VALUE : (double) sum / count;
    }

    /**
     * Top Y of a FLOATING bottom cluster (translucent dock / pill bar,
     * possibly with a round floating button just above it), or -1.
     * Unlike staticBottomHeight this does not require the cluster to
     * touch the frame's bottom edge: it looks for a band of rows in
     * the bottom half that are near-identical in both frames (the
     * relaxed 12 threshold tolerates a translucent bar whose pixels
     * shift slightly with the content behind it), ending in the
     * bottom 18% of the frame, at least 48px tall. A detail-cell
     * check above the band catches an opaque round button floating
     * just above the bar (its rows alone are mostly content, so the
     * row test cannot see it, but its 32px cells are static AND
     * detailed, while coincidentally static white content cells are
     * not detailed).
     */
    /** Returns {clusterTop, extendedTop} in original px, or null. */
    public static int[] floatingBottomInfo(Bitmap a, Bitmap b) {
        if (a == null || b == null) return null;
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return null;
        int w = a.getWidth();
        int h = a.getHeight();
        // Work on a small grayscale pair (width 200): averaging kills
        // the per-pixel variance that translucency adds, and all the
        // tests below run on bands, not single rows.
        int sw = 200;
        int sh = Math.max(1, (int) Math.round((double) sw * h / w));
        int[] grayA = toGraySmall(a, sw);
        int[] grayB = toGraySmall(b, sw);
        if (grayA == null || grayB == null) return null;
        double scaleY = (double) h / sh;
        int bandRows = Math.max(4, (int) Math.round(24 / scaleY)); // ~24 original px
        int x0 = (int) (sw * 0.10);
        int x1 = (int) (sw * 0.90);
        int regionTopSmall = (int) (sh * 0.50);
        // bandStatic[y] for band tops y in small rows. Two tests, either
        // is enough: the plain mean (< 10), and a TRIMMED mean (< 12)
        // that discards the top quarter of sample diffs. The trimmed
        // test exists because a real dock is not pixel-stable as a
        // whole: one of its segments changes state between the first
        // two frames (e.g. the left icon flips from "home" to "back
        // to top" as soon as the page scrolls), and whatever plays
        // behind a translucent pill (video thumbnails) changes a lot
        // too. Those changed samples are a minority of the band; the
        // plain mean lets them veto the whole detection (the browser
        // news page in the v1.17 test: dock repeated in every frame
        // because detection returned -1). Genuine content bands keep
        // failing both tests: their diffs are spread over most samples.
        boolean[] bandStatic = new boolean[sh];
        int[] samples = new int[1024];
        for (int y = regionTopSmall; y + bandRows <= sh; y++) {
            long sum = 0;
            int count = 0;
            for (int r = 0; r < bandRows; r += 2) {
                int rowA = (y + r) * sw;
                int rowB = (y + r) * sw;
                for (int x = x0; x < x1; x += 2) {
                    int d = Math.abs(grayB[rowB + x] - grayA[rowA + x]);
                    sum += d;
                    if (count < samples.length) samples[count] = d;
                    count++;
                }
            }
            if (count == 0) continue;
            double mean = (double) sum / count;
            boolean isStatic = mean < 10.0;
            if (!isStatic && count <= samples.length) {
                java.util.Arrays.sort(samples, 0, count);
                int keep = Math.max(1, (int) (count * 0.75));
                long tSum = 0;
                for (int i = 0; i < keep; i++) tSum += samples[i];
                // Trimmed pass additionally requires the plain mean to
                // stay moderate: sparse text bands can sneak under the
                // trimmed threshold alone (most of their samples are
                // blank in both frames), but their mean stays high.
                isStatic = (double) tSum / keep < 12.0 && mean < 28.0;
            }
            bandStatic[y] = isStatic;
        }
        // Group the static bands into runs (gaps of <= 2 non-static
        // bands allowed) and choose the LOWEST run that is at least
        // ~40px tall and ends in the bottom 18% of the frame.
        // Anchoring on the single lowest static band instead is wrong:
        // an isolated near-static content band below the dock (a pale
        // margin, one sparse text band) would anchor a tiny "cluster"
        // there, fail the height check, and the real dock above it
        // would never be considered - exactly what the first
        // trimmed-mean version did on the test bench.
        int bestTop = -1;
        int bestBottom = -1;
        int y = regionTopSmall;
        while (y + bandRows <= sh) {
            if (!bandStatic[y]) { y++; continue; }
            int runTop = y;
            int lastStatic = y;
            int gap = 0;
            int yy = y + 1;
            while (yy + bandRows <= sh) {
                if (bandStatic[yy]) {
                    lastStatic = yy;
                    gap = 0;
                } else {
                    gap++;
                    if (gap > 2) break;
                }
                yy++;
            }
            int runBottom = lastStatic + bandRows;
            int topPx = (int) Math.round(runTop * scaleY);
            int bottomPx = (int) Math.round(runBottom * scaleY);
            if (bottomPx - topPx >= 40 && runBottom >= (int) (sh * 0.82)) {
                if (bestTop < 0 || runTop > bestTop) {
                    bestTop = runTop;
                    bestBottom = runBottom;
                }
            }
            y = Math.max(yy, y + 1);
        }
        if (bestTop < 0) return null;
        int top = (int) Math.round(bestTop * scaleY);
        int clusterTop = top;
        // Floating-element extension: detailed static cells above
        // the cluster, grouped into connected clusters of >= 2
        // cells; the highest qualifying cluster extends the cut.
        // The scan window reaches 640px above the cluster: on
        // Hayes's real page the round refresh button floats with
        // a content gap between it and the pill, its top ~385px
        // above the pill's top, and the old 240px window truncated
        // the extension mid-button in EVERY capture (extension
        // consistently stopped at cluster-240), leaving the
        // button's top cap in every frame - a half button at every
        // seam. Single stray cells do not extend the cut (a lone
        // static cell can be a paused video's corner); a real
        // floating element is a connected group of edge cells.
        // Anything pixel-static across a full scroll step inside
        // this bottom region is a fixed element by definition, so
        // the wider window stays principled.
        int cellTop = top;
        int cell = 32;
        int bandStart = Math.max((int) (h * 0.45), top - 640);
        int cols = (w + cell - 1) / cell;
        int rowsN = Math.max(0, (top - bandStart + cell - 1) / cell);
        boolean[][] pass = new boolean[rowsN][cols];
        for (int r = 0; r < rowsN; r++) {
            int y0 = bandStart + r * cell;
            int ch = Math.min(cell, top - y0);
            if (ch <= 0) continue;
            for (int c = 0; c < cols; c++) {
                int cx0 = c * cell;
                int cw = Math.min(cell, w - cx0);
                if (cw <= 0) continue;
                pass[r][c] = isStaticDetailedCellTrimmed(a, b, cx0, y0, cw, ch);
            }
        }
        boolean[][] seen = new boolean[rowsN][cols];
        int[] stackR = new int[Math.max(1, rowsN * cols)];
        int[] stackC = new int[Math.max(1, rowsN * cols)];
        for (int r = 0; r < rowsN; r++) {
            for (int c = 0; c < cols; c++) {
                if (!pass[r][c] || seen[r][c]) continue;
                int sp = 0;
                stackR[sp] = r; stackC[sp] = c; sp++;
                seen[r][c] = true;
                int size = 0;
                int minR = r, maxR = r;
                int minC = c, maxC = c;
                while (sp > 0) {
                    sp--;
                    int cr = stackR[sp], cc = stackC[sp];
                    size++;
                    if (cr < minR) minR = cr;
                    if (cr > maxR) maxR = cr;
                    if (cc < minC) minC = cc;
                    if (cc > maxC) maxC = cc;
                    int[][] nb = {{cr - 1, cc}, {cr + 1, cc}, {cr, cc - 1}, {cr, cc + 1}};
                    for (int[] q : nb) {
                        int nr = q[0], nc = q[1];
                        if (nr < 0 || nc < 0 || nr >= rowsN || nc >= cols) continue;
                        if (!pass[nr][nc] || seen[nr][nc]) continue;
                        seen[nr][nc] = true;
                        stackR[sp] = nr; stackC[sp] = nc; sp++;
                    }
                }
                // Footprint >= 2x2 cells: on Hayes's capture a
                // one-cell-wide vertical cluster of browser-
                // scrollbar cells reached the window's top on a
                // long-baseline pair (a thin sliver must not set
                // the cut), and on the bench's synthetic docs a
                // one-cell-TALL row of block-edge coincidences -
                // the doc pattern repeating at exactly the scroll
                // step - corroborated itself across pairs at a
                // fixed screen position. Real floating buttons
                // and badges are two-dimensional: at least ~64px
                // in both directions. (Thin wide bars are the
                // band detector's job, not the extension's.)
                if (size >= 2 && maxC - minC + 1 >= 2 && maxR - minR + 1 >= 2) {
                    int yTop = bandStart + minR * cell;
                    if (yTop < cellTop) cellTop = yTop;
                }
            }
        }
        // One-cell margin above the highest detected cell: an
        // element's rim can hide in the cell above its first
        // detailed-static one (partial coverage - on Hayes's
        // capture the refresh disc's top arc occupies the cell
        // above its first passing cell, and a flush cut left a
        // visible arc cap at every seam). A uniform cut stays
        // continuous at any height (the body ends at the last
        // frame's cut line and the strip continues from exactly
        // there), so the margin costs only a cell of overlap.
        if (cellTop < top) top = Math.max(0, cellTop - cell - 4);
        // Keep enough content to stitch: the floating cut
        // (dock + extension) is capped at 40% of the frame
        // height. Hayes's real complex is ~27%; beyond ~40%
        // the remaining content band gets too thin for reliable
        // overlap matching at normal scroll steps, whatever the
        // cells above claim to be.
        int minTop = (int) Math.round(h * 0.60);
        if (top < minTop) top = minTop;
        return new int[]{clusterTop, top};
    }

    /**
     * Top of the floating bottom cluster (dock pill + any floating
     * button above it), or -1. Wrapper over floatingBottomInfo; the
     * info form also exposes the pre-extension cluster top, which is
     * the stable value cross-pair agreement should compare: the
     * extension is bimodal (it either catches a floating button or
     * not, ~100+px apart), so two genuine detections of the same
     * dock can disagree on the extended top while agreeing exactly
     * on the cluster top.
     */
    public static int floatingBottomTop(Bitmap a, Bitmap b) {
        int[] info = floatingBottomInfo(a, b);
        return info == null ? -1 : info[1];
    }

    /**
     * Top of the highest qualifying static-cell cluster in the
     * bottom region, or -1. The band-based floatingBottomInfo only
     * sees elements that span most of the width (a dock); a lone
     * floating button (back-to-top, refresh) is narrow, so it is
     * found here instead: 32px cells that are detailed AND static
     * across the pair are grouped into connected clusters, and a
     * cluster qualifies with >= 2 cells and its bottom in the
     * bottom 28% of the frame. This is shape-agnostic - any fixed
     * component in the bottom region (button, mini toolbar, chat
     * head) forms such a cluster, while scrolled content cannot
     * stay static in a screen-fixed cell.
     */
    public static int floatingCellClusterTop(Bitmap a, Bitmap b) {
        if (a == null || b == null) return -1;
        int w = a.getWidth(), h = a.getHeight();
        if (b.getWidth() != w || b.getHeight() != h) return -1;
        // 24px cells: small enough that several fit fully inside a
        // typical 56-72px floating button (a 32px cell straddles
        // the button's edge and its corners show scrolled content,
        // which vetoes the cell). A trimmed-mean cell test was
        // tried instead and falsely fired all over periodic
        // content; exact small cells are the safer discriminator.
        int cell = 24;
        int regionTop = (int) (h * 0.55);
        int cols = (w + cell - 1) / cell;
        int rows = (h - regionTop + cell - 1) / cell;
        if (rows <= 0 || cols <= 0) return -1;
        boolean[][] pass = new boolean[rows][cols];
        for (int r = 0; r < rows; r++) {
            int y0 = regionTop + r * cell;
            int ch = Math.min(cell, h - y0);
            for (int c = 0; c < cols; c++) {
                int x0 = c * cell;
                int cw = Math.min(cell, w - x0);
                pass[r][c] = isStaticDetailedCell(a, b, x0, y0, cw, ch);
            }
        }
        boolean[][] seen = new boolean[rows][cols];
        int bestTop = -1;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (!pass[r][c] || seen[r][c]) continue;
                java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
                q.add(new int[]{r, c});
                seen[r][c] = true;
                int count = 0, topR = r, botR = r, minC = c, maxC = c;
                while (!q.isEmpty()) {
                    int[] cur = q.poll();
                    count++;
                    if (cur[0] < topR) topR = cur[0];
                    if (cur[0] > botR) botR = cur[0];
                    if (cur[1] < minC) minC = cur[1];
                    if (cur[1] > maxC) maxC = cur[1];
                    for (int dr = -1; dr <= 1; dr++) {
                        for (int dc = -1; dc <= 1; dc++) {
                            int nr = cur[0] + dr, nc = cur[1] + dc;
                            if (nr < 0 || nc < 0 || nr >= rows || nc >= cols) continue;
                            if (!pass[nr][nc] || seen[nr][nc]) continue;
                            seen[nr][nc] = true;
                            q.add(new int[]{nr, nc});
                        }
                    }
                }
                int clusterBottom = regionTop + (botR + 1) * cell;
                // Position prior: floating UI lives at the screen
                // edges (side buttons, chat heads) or spans most of
                // the width (mini toolbars, docks). A static cluster
                // floating in mid-content is far more likely to be
                // self-similar page content coinciding across one
                // scroll step - periodic layouts produce those at
                // some step sizes, and they must never cut.
                int clusterLeft = minC * cell;
                int clusterRight = (maxC + 1) * cell;
                boolean atEdge = clusterLeft <= (int) (w * 0.06)
                        || clusterRight >= (int) (w * 0.94)
                        || (clusterRight - clusterLeft) >= (int) (w * 0.60);
                if (count >= 2 && atEdge && clusterBottom >= (int) (h * 0.72)) {
                    int clusterTop = regionTop + topR * cell;
                    if (bestTop < 0 || clusterTop < bestTop) bestTop = clusterTop;
                }
            }
        }
        return bestTop;
    }

    /** True if a cell is near-identical in both frames AND has real detail (not a flat area). */
    private static boolean isStaticDetailedCell(Bitmap a, Bitmap b, int x0, int y0, int cw, int ch) {
        int min = 255, max = 0;
        int[] diffs = new int[256];
        int count = 0;
        for (int y = y0; y < y0 + ch; y += 4) {
            for (int x = x0; x < x0 + cw; x += 4) {
                int pa = a.getPixel(x, y);
                int pb = b.getPixel(x, y);
                int g = (((pa >> 16) & 0xff) * 30 + ((pa >> 8) & 0xff) * 59 + (pa & 0xff) * 11) / 100;
                if (g < min) min = g;
                if (g > max) max = g;
                if (count < diffs.length - 3) {
                    diffs[count++] = Math.abs(((pa >> 16) & 0xff) - ((pb >> 16) & 0xff));
                    diffs[count++] = Math.abs(((pa >> 8) & 0xff) - ((pb >> 8) & 0xff));
                    diffs[count++] = Math.abs((pa & 0xff) - (pb & 0xff));
                }
            }
        }
        if (count == 0 || (max - min) <= 40) return false;
        long sum = 0;
        for (int i = 0; i < count; i++) sum += diffs[i];
        return (double) sum / count < 8.0;
    }

    // Static-only sibling of the trimmed cell test (no detail
    // requirement): a floating element's rim and face are
    // static but NOT detailed (a white disc's interior has no
    // gray range), so growing a detected element to its true
    // bounds needs the static verdict on its own.
    private static boolean isStaticCellTrimmed(Bitmap a, Bitmap b, int x0, int y0, int cw, int ch) {
        double[] pointDiffs = new double[64];
        int count = 0;
        for (int y = y0; y < y0 + ch; y += 4) {
            for (int x = x0; x < x0 + cw; x += 4) {
                int pa = a.getPixel(x, y);
                int pb = b.getPixel(x, y);
                if (count < pointDiffs.length) {
                    pointDiffs[count++] = (Math.abs(((pa >> 16) & 0xff) - ((pb >> 16) & 0xff))
                            + Math.abs(((pa >> 8) & 0xff) - ((pb >> 8) & 0xff))
                            + Math.abs((pa & 0xff) - (pb & 0xff))) / 3.0;
                }
            }
        }
        if (count == 0) return false;
        double sum = 0;
        for (int i = 0; i < count; i++) sum += pointDiffs[i];
        double mean = sum / count;
        if (mean < 8.0) return true;
        java.util.Arrays.sort(pointDiffs, 0, count);
        int keep = Math.max(1, (int) (count * 0.75));
        double tSum = 0;
        for (int i = 0; i < keep; i++) tSum += pointDiffs[i];
        return tSum / keep < 10.0 && mean < 24.0;
    }

    // Mid-screen fixed elements (v1.30): every qualifying
    // static+detailed cell cluster in the frame, as rows
    // {x0, y0, x1, y1, coreY0, coreY1} (exclusive): the dilated
    // box to fill, plus the detailed core's rows for occlusion
    // tests. A cluster qualifies with the
    // same footprint discipline as the bottom extension (>= 2x2
    // cells - real floating widgets are two-dimensional) and one
    // additional test that separates an OCCLUDER from layout
    // chrome: for a widget floating OVER the document, the rows
    // it spans are still scrolling OUTSIDE its columns (band
    // mean diff high); for a layout bar (an app header the
    // sticky-top measurement stopped short of, a tab strip) the
    // whole band is static (mean diff < 6) and the rectangle is
    // rejected - filling a bar's rows with document pixels from
    // other frames would corrupt it, while an occluder's rows
    // are exactly what the deferred fill can restore. A
    // full-width cluster has no outside columns and is treated
    // as a bar (wide elements are the band detector's job).
    // Confirmation across pairs (different scroll-step scales)
    // is the caller's job, mirroring the cell-detector
    // discipline: a single pair's clusters can include content
    // coincidences.
    public static int[][] fixedElementRects(Bitmap a, Bitmap b) {
        if (a == null || b == null) return new int[0][];
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return new int[0][];
        int w = a.getWidth(), h = a.getHeight();
        int cell = 32;
        int cols = (w + cell - 1) / cell;
        int rowsN = (h + cell - 1) / cell;
        boolean[][] det = new boolean[rowsN][cols];
        for (int r = 0; r < rowsN; r++) {
            int y0 = r * cell;
            int ch = Math.min(cell, h - y0);
            for (int c = 0; c < cols; c++) {
                int cx0 = c * cell;
                int cw = Math.min(cell, w - cx0);
                det[r][c] = isStaticDetailedCellTrimmed(a, b, cx0, y0, cw, ch);
            }
        }
        // Detailed cores first (components of detailed+static
        // cells, >= 2 cells spanning >= 2x2 - the footprint
        // discipline). A core UNDERSTATES the element: its
        // featureless face is static but not detailed, and its
        // rim cells are majority scrolling content, so neither
        // passes the detailed test; growing the core over
        // merely-static cells is not an option either - on a
        // white-background feed the static network bleeds from
        // the element through every card margin and merges it
        // with the page furniture (measured on Hayes's capture:
        // the component spanned the whole left margin). So the
        // core's box is DILATED by a fixed 5 cells (160px): an
        // over-generous box is safe for the fill (it copies the
        // same document row from another frame - identical
        // content where no element is present), while an
        // under-sized one leaves the element's rim drawn at
        // every seam. The guards that keep dilation honest are
        // the occluder test on the dilated box (a static band
        // outside it means layout chrome) and the height cap
        // (a box taller than half the frame is a vertical
        // layout strip, not an occluder).
        java.util.List<int[]> cores = new java.util.ArrayList<>();
        boolean[][] seen = new boolean[rowsN][cols];
        int[] stackR = new int[Math.max(1, rowsN * cols)];
        int[] stackC = new int[Math.max(1, rowsN * cols)];
        for (int r = 0; r < rowsN; r++) {
            for (int c = 0; c < cols; c++) {
                if (!det[r][c] || seen[r][c]) continue;
                int sp = 0;
                stackR[sp] = r; stackC[sp] = c; sp++;
                seen[r][c] = true;
                int size = 0;
                int minR = r, maxR = r, minC = c, maxC = c;
                while (sp > 0) {
                    sp--;
                    int cr = stackR[sp], cc = stackC[sp];
                    size++;
                    if (cr < minR) minR = cr;
                    if (cr > maxR) maxR = cr;
                    if (cc < minC) minC = cc;
                    if (cc > maxC) maxC = cc;
                    int[][] nb = {{cr - 1, cc}, {cr + 1, cc}, {cr, cc - 1}, {cr, cc + 1}};
                    for (int[] q : nb) {
                        int nr = q[0], nc = q[1];
                        if (nr < 0 || nc < 0 || nr >= rowsN || nc >= cols) continue;
                        if (!det[nr][nc] || seen[nr][nc]) continue;
                        seen[nr][nc] = true;
                        stackR[sp] = nr; stackC[sp] = nc; sp++;
                    }
                }
                if (size < 2 || maxC - minC + 1 < 2 || maxR - minR + 1 < 2) continue;
                cores.add(new int[]{minC * cell, minR * cell,
                        Math.min(w, (maxC + 1) * cell), Math.min(h, (maxR + 1) * cell), size});
            }
        }
        cores.sort((x, y) -> Integer.compare(y[4], x[4]));
        java.util.List<int[]> out = new java.util.ArrayList<>();
        int dil = cell * 5;
        for (int[] core : cores) {
            if (out.size() >= 8) break;
            int x0 = Math.max(0, core[0] - dil), y0 = Math.max(0, core[1] - dil);
            int x1 = Math.min(w, core[2] + dil), y1 = Math.min(h, core[3] + dil);
            if (y1 - y0 > h * 0.5) continue;
            // Occluder test: the band outside the box must MOVE.
            long dSum = 0;
            int dCnt = 0;
            for (int y = y0; y < y1; y += 4) {
                for (int x = 0; x < w; x += 4) {
                    if (x >= x0 && x < x1) continue;
                    dSum += Math.abs(grayOf(a.getPixel(x, y)) - grayOf(b.getPixel(x, y)));
                    dCnt++;
                }
            }
            if (dCnt == 0) continue;              // full-width: a bar
            if ((double) dSum / dCnt < 6.0) continue; // static band: layout chrome
            // {dilated x0, y0, x1, y1, core y0, core y1}: the
            // caller fills the dilated box but tests occlusion
            // against the CORE rows - a source row is only
            // distrusted while it sits where the element's
            // texture was actually seen; the dilation band
            // around the core is where the element's
            // featureless rim may live, and rows there make
            // perfectly good fill sources (they are clear of
            // the textured body, and a rim's own pixels are
            // near-uniform anyway).
            out.add(new int[]{x0, y0, x1, y1, core[1], core[3]});
        }
        return out.toArray(new int[0][]);
    }

    // Copy one row segment (columns [x0, x1) of srcRow in src)
    // into dst at dstRow - the deferred-fill primitive for
    // occluded rows: a document row hidden behind a fixed
    // element in the frame it was appended from is restored
    // from a later frame where the same document row sits
    // clear of the element.
    public static void fillRowSegment(Bitmap dst, int dstRow, Bitmap src, int srcRow, int x0, int x1) {
        if (dst == null || src == null) return;
        if (srcRow < 0 || srcRow >= src.getHeight()) return;
        if (dstRow < 0 || dstRow >= dst.getHeight()) return;
        int lo = Math.max(0, x0);
        int hi = Math.min(Math.min(dst.getWidth(), src.getWidth()), x1);
        if (hi <= lo) return;
        Bitmap seg = Bitmap.createBitmap(src, lo, srcRow, hi - lo, 1);
        new android.graphics.Canvas(dst).drawBitmap(seg, lo, dstRow, null);
    }

    // Extension-only cell test (v1.29): like isStaticDetailedCell
    // but the static test ALSO passes on a trimmed mean - a
    // floating element's edge cell mixes the element's own static
    // pixels (the majority: the disc's face) with the page content
    // moving behind its translucent rim (the minority). On Hayes's
    // real capture the refresh button's ring cells failed the
    // strict test whenever the card behind it was a dark video,
    // so the extension stopped ~250px short on those pairs. The
    // strict test stays in force for the standalone cell-cluster
    // detector, where a trimmed test produced false clusters on
    // periodic content (v1.24); the extension context is anchored
    // on a confirmed band cluster and additionally requires a
    // connected >= 2-cell cluster, which is what keeps the looser
    // test safe here.
    private static boolean isStaticDetailedCellTrimmed(Bitmap a, Bitmap b, int x0, int y0, int cw, int ch) {
        int min = 255, max = 0;
        double[] pointDiffs = new double[64];
        int count = 0;
        for (int y = y0; y < y0 + ch; y += 4) {
            for (int x = x0; x < x0 + cw; x += 4) {
                int pa = a.getPixel(x, y);
                int pb = b.getPixel(x, y);
                int g = (((pa >> 16) & 0xff) * 30 + ((pa >> 8) & 0xff) * 59 + (pa & 0xff) * 11) / 100;
                if (g < min) min = g;
                if (g > max) max = g;
                if (count < pointDiffs.length) {
                    double d = (Math.abs(((pa >> 16) & 0xff) - ((pb >> 16) & 0xff))
                            + Math.abs(((pa >> 8) & 0xff) - ((pb >> 8) & 0xff))
                            + Math.abs((pa & 0xff) - (pb & 0xff))) / 3.0;
                    pointDiffs[count++] = d;
                }
            }
        }
        if (count == 0 || (max - min) <= 40) return false;
        double sum = 0;
        for (int i = 0; i < count; i++) sum += pointDiffs[i];
        double mean = sum / count;
        if (mean < 8.0) return true;
        java.util.Arrays.sort(pointDiffs, 0, count);
        int keep = Math.max(1, (int) (count * 0.75));
        double tSum = 0;
        for (int i = 0; i < keep; i++) tSum += pointDiffs[i];
        return tSum / keep < 10.0 && mean < 24.0;
    }

    /** Append b below a using a known overlap (append-only drawing). */
    public static Result stitchVerticalWithOverlap(Bitmap a, Bitmap b, int overlap, boolean matched) {
        if (a == null) return new Result(b, 0, false);
        if (b == null) return new Result(a, 0, false);
        Bitmap bb = b;
        boolean scaledCopy = false;
        if (bb.getWidth() != a.getWidth()) {
            bb = Bitmap.createScaledBitmap(b, a.getWidth(),
                    (int) Math.round((double) b.getHeight() * a.getWidth() / b.getWidth()), true);
            scaledCopy = true;
        }
        int w = a.getWidth();
        int hA = a.getHeight();
        int hB = bb.getHeight();
        if (overlap < 0) overlap = 0;
        if (overlap >= hB) overlap = hB - 1;
        int cap = 40000;
        int drawH = Math.min(hB - overlap, Math.max(0, cap - hA));
        Bitmap out = Bitmap.createBitmap(w, hA + drawH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawBitmap(a, 0, 0, null);
        if (drawH > 0) {
            // Append ONLY the new part of b (below the overlap). Drawing
            // all of b over a would re-paint sticky / floating elements
            // and any mis-aligned content into the middle of the image.
            Bitmap part = Bitmap.createBitmap(bb, 0, overlap, bb.getWidth(), drawH);
            canvas.drawBitmap(part, 0, hA, null);
            part.recycle();
        }
        if (scaledCopy) bb.recycle();
        return new Result(out, overlap, matched);
    }

    /**
     * Boundary duplicate guard. At a very large overlap (near the
     * page bottom, where the last gesture can only move a short
     * distance) the matcher can lock one item-block too high and
     * append a copy of the block that is already at the canvas
     * tail. This capture showed exactly that: the final frame had
     * one "修罗剑神", but the saved image had two.
     *
     * Compares a's last p rows with b's first p rows at the overlap.
     * If a full content block is identical (near-zero difference,
     * non-blank), the correct overlap includes that block; returns
     * its height so the caller can re-stitch with overlap + p.
     * Only called for high-overlap (page-bottom-like) joins.
     */
    /**
     * v1.39.47: corroboration score for a PROPOSED overlap that
     * the matcher itself did not certify (the session step-prior
     * fallback in CaptureService). Compares a's last `overlap`
     * rows with b's first `overlap` rows directly and returns
     * the MEDIAN per-row gray difference (rows sampled every 3,
     * x sampled ~160 across). A genuinely overlapping pair
     * agrees almost everywhere, so the median row is near-zero
     * even when a video card or a rotating banner poisons a
     * minority of rows; unrelated content (a true content gap,
     * or a prior that is off by a whole item) disagrees on most
     * rows and reads high. Returns -1 when the proposed overlap
     * cannot be evaluated. Calibrated offline on captures
     * 43/65/67 before shipping (see Probe68).
     */
    public static double overlapZoneMedianDiff(Bitmap a, Bitmap b, int overlap) {
        if (a == null || b == null || overlap <= 0) return -1;
        int w = Math.min(a.getWidth(), b.getWidth());
        int ha = a.getHeight(), hb = b.getHeight();
        if (overlap > ha || overlap > hb || w <= 0) return -1;
        int xStep = Math.max(1, w / 160);
        java.util.ArrayList<Double> rows = new java.util.ArrayList<>();
        for (int y = 0; y < overlap; y += 3) {
            long sum = 0;
            int n = 0;
            int ya = ha - overlap + y;
            for (int x = 0; x < w; x += xStep) {
                sum += Math.abs(grayOf(a.getPixel(x, ya)) - grayOf(b.getPixel(x, y)));
                n++;
            }
            if (n > 0) rows.add((double) sum / n);
        }
        if (rows.isEmpty()) return -1;
        java.util.Collections.sort(rows);
        return rows.get(rows.size() / 2);
    }

    /**
     * v1.39.50: fraction of sampled zone rows whose mean gray
     * difference is at most 12 - the STATIC-ROW AGREEMENT of a
     * proposed overlap. Sampling mirrors overlapZoneMedianDiff
     * (rows every 3, ~160 columns). A row of genuinely shared
     * content agrees almost exactly; a row inside a playing
     * video or an unrelated region does not. The fraction -
     * not the median - is the motion-tolerant statistic: a
     * video-heavy but real overlap still has its static
     * majority voting (titles, images, source lines), while a
     * true content gap has almost no agreeing rows anywhere.
     */
    private static double zoneRowAgreement(Bitmap a, Bitmap b, int overlap) {
        if (a == null || b == null || overlap <= 0) return -1;
        int w = Math.min(a.getWidth(), b.getWidth());
        int ha = a.getHeight(), hb = b.getHeight();
        if (overlap > ha || overlap > hb || w <= 0) return -1;
        int xStep = Math.max(1, w / 160);
        int agree = 0, total = 0;
        for (int y = 0; y < overlap; y += 3) {
            long sum = 0;
            int n = 0;
            int ya = ha - overlap + y;
            for (int x = 0; x < w; x += xStep) {
                sum += Math.abs(grayOf(a.getPixel(x, ya)) - grayOf(b.getPixel(x, y)));
                n++;
            }
            if (n > 0) {
                total++;
                if ((double) sum / n <= 12.0) agree++;
            }
        }
        return total == 0 ? -1 : (double) agree / total;
    }

    /**
     * v1.39.50: verify a step-prior fallback by AGREEMENT SCAN
     * instead of the v1.39.47 +-6 median probe. The joins that
     * reach the prior are disproportionately atypical steps -
     * capture 70/71's step came up ~185px short, putting the
     * truth ~750px from the prior, far outside any +-6 window,
     * and their overlap zones were dominated by two playing
     * videos, so the whole-zone median read 57-78 and the
     * healthy join was rejected. The scan evaluates the
     * static-row agreement over every overlap whose implied
     * step lies in [0.45x, 1.60x] the session median step
     * (stride 8, refined +-8 by 2) and accepts the argmax when
     * its agreement reaches 0.20. Offline calibration
     * (ProbeVerify, dump scale): capture 70/71 accept at the
     * true overlap (agreement 0.33), capture 43's momentum
     * outlier accepts at its true overlap (0.31), capture 67's
     * healthy prior accepts at the prior (0.57), a WeChat pair
     * accepts at its true overlap (0.78), and capture 65's
     * true content gap reaches at most 0.08 ANYWHERE in the
     * scan - the gate sits between the weakest accept (0.31)
     * and the strongest reject (0.08) with >1.5x margin both
     * ways. Returns {acceptedOverlap or -1, bestAgreement x1000,
     * bestOverlap}.
     */
    public static int[] verifyStepPrior(Bitmap a, Bitmap b, int priorOverlap, int medianStep) {
        int failBest = -1;
        double failFrac = -1;
        if (a == null || b == null || medianStep <= 0) return new int[]{-1, -1, -1};
        int hB = b.getHeight();
        int stepLo = Math.max(60, (int) (0.45 * medianStep));
        int stepHi = (int) (1.60 * medianStep);
        int ovHi = Math.min((int) (hB * 0.95), hB - stepLo);
        int ovLo = Math.max(Math.max(24, (int) (hB * 0.05)), hB - stepHi);
        if (ovLo > ovHi) return new int[]{-1, -1, -1};
        int bestOv = -1;
        double bestF = -1;
        for (int ov = ovLo; ov <= ovHi; ov += 8) {
            double f = zoneRowAgreement(a, b, ov);
            if (f > bestF) { bestF = f; bestOv = ov; }
        }
        if (bestOv > 0) {
            for (int ov = Math.max(ovLo, bestOv - 8); ov <= Math.min(ovHi, bestOv + 8); ov += 2) {
                double f = zoneRowAgreement(a, b, ov);
                if (f > bestF) { bestF = f; bestOv = ov; }
            }
        }
        failBest = bestOv;
        failFrac = bestF;
        if (bestOv > 0 && bestF >= 0.20) {
            return new int[]{bestOv, (int) Math.round(bestF * 1000), bestOv};
        }
        return new int[]{-1, (int) Math.round(Math.max(0, failFrac) * 1000), failBest};
    }

    public static int duplicateBlockHeight(Bitmap a, Bitmap b, int overlap) {
        if (a == null || b == null) return 0;
        if (a.getWidth() != b.getWidth()) return 0;
        int w = a.getWidth();
        int hA = a.getHeight();
        int hB = b.getHeight();
        if (w <= 0 || overlap < 0 || overlap >= hB) return 0;
        // v1.39.45: the cap was 360, but capture 52's duplicated
        // block measured 452 device px (a full history card):
        // at the page end the true step collapsed to ~68 px,
        // the matcher aliased one card up on the self-similar
        // list, and the guard's ceiling sat below the very
        // block it exists to catch. The ceiling is now half
        // the frame height - the largest block that can still
        // be a duplicate of the canvas tail while leaving a
        // real contribution below it. Offline A/B at device
        // scale: fires 454 on capture 52's last join, 0 on all
        // 25 healthy joins of captures 49/50/52.
        int maxP = Math.min(hB / 2, Math.min(hA, hB - overlap - 32));
        int minP = 80;
        if (maxP < minP) return 0;
        int stepX = Math.max(3, w / 200);
        int bestP = 0;
        // Step 2, not 4: an item block height is arbitrary (the
        // observed row is even, and a 4px stride could miss the
        // exact height by 2px, turning an identical block into a
        // mismatched one in the row-offset comparison).
        for (int p = minP; p <= maxP; p += 2) {
            double sum = 0;
            int n = 0;
            double edge = 0;
            int en = 0;
            for (int y = 0; y < p; y += 4) {
                int ay = hA - p + y;
                int by = overlap + y;
                if (ay < 0 || by >= hB) continue;
                for (int x = 0; x < w; x += stepX) {
                    int ga = grayOf(a.getPixel(x, ay));
                    int gb = grayOf(b.getPixel(x, by));
                    sum += Math.abs(ga - gb);
                    n++;
                    if (y + 4 < p) {
                        edge += Math.abs(ga - grayOf(a.getPixel(x, ay + 4)));
                        en++;
                    }
                }
            }
            if (n == 0) continue;
            double diff = sum / n;
            double energy = en > 0 ? edge / en : 0;
            // Near-identical non-blank block: a duplicated item.
            // Keep the largest such p so the whole item (not just
            // one half of it) moves into the overlap.
            if (diff < 7.0 && energy > 8.0 && p > bestP) bestP = p;
        }
        return bestP;
    }

    /**
     * Seam-placing variant used by the capture loop: minSeamRow is
     * the bottom of any screen-fixed chrome still present inside
     * the content frames (in b's coordinates). Rows above it are
     * NOT document rows - a white chrome margin can coincidentally
     * "agree" with a white document gap, and splicing there would
     * import a copy of the chrome into the middle of the document
     * and discard the real rows (capture 7 analysis: the browser's
     * tab bar, whose true static height the rotating search hint
     * hides from the sticky-top cut). Candidates are restricted to
     * [minSeamRow, overlap).
     */
    public static Result stitchVerticalWithOverlap(Bitmap a, Bitmap b, int overlap,
                                                   boolean matched, int minSeamRow) {
        if (a == null) return new Result(b, 0, false);
        if (b == null) return new Result(a, 0, false);
        Bitmap bb = b;
        boolean scaledCopy = false;
        if (bb.getWidth() != a.getWidth()) {
            bb = Bitmap.createScaledBitmap(b, a.getWidth(),
                    (int) Math.round((double) b.getHeight() * a.getWidth() / b.getWidth()), true);
            scaledCopy = true;
        }
        int w = a.getWidth();
        int hA = a.getHeight();
        int hB = bb.getHeight();
        if (overlap < 0) overlap = 0;
        if (overlap >= hB) overlap = hB - 1;
        int seam = overlap;
        if (matched && overlap >= 16 && hA >= overlap) {
            seam = chooseSeamRow(a, bb, overlap, Math.max(0, minSeamRow));
        }
        int keepA = hA - overlap + seam;
        if (keepA < 1) keepA = 1;
        int cap = 40000;
        int drawH = Math.min(hB - seam, Math.max(0, cap - keepA));
        Bitmap out = Bitmap.createBitmap(w, keepA + drawH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        if (keepA == hA) {
            canvas.drawBitmap(a, 0, 0, null);
        } else {
            Bitmap top = Bitmap.createBitmap(a, 0, 0, w, keepA);
            canvas.drawBitmap(top, 0, 0, null);
            top.recycle();
        }
        if (drawH > 0) {
            Bitmap part = Bitmap.createBitmap(bb, 0, seam, bb.getWidth(), drawH);
            canvas.drawBitmap(part, 0, keepA, null);
            part.recycle();
        }
        if (scaledCopy) bb.recycle();
        return new Result(out, seam, matched);
    }

    /**
     * Pick the splice row inside the overlap: the row (in b's
     * coordinates, 0..overlap) whose windowed row agreement between
     * a's tail and b's head is best, or `overlap` (the nominal
     * bottom seam) when no row is convincingly better. Candidates
     * start at minSeamRow (screen-fixed chrome guard, see above).
     */
    private static int chooseSeamRow(Bitmap a, Bitmap b, int overlap, int minSeamRow) {
        int w = a.getWidth();
        int hA = a.getHeight();
        int stepX = Math.max(1, w / 160);
        int[] rowA = new int[w];
        int[] rowB = new int[w];
        double[] diff = new double[overlap];
        double[] detB = new double[overlap];
        int[] grayB = new int[w / stepX + 2];
        for (int r = 0; r < overlap; r++) {
            a.getPixels(rowA, 0, w, 0, hA - overlap + r, w, 1);
            b.getPixels(rowB, 0, w, 0, r, w, 1);
            int gn = 0;
            // Row agreement = MEDIAN over 6 vertical strips of
            // the strips' mean differences (the same consensus
            // idea the matcher itself uses). A localized occluder
            // covering a strip or two - a system volume HUD at
            // the screen edge is the proven case - is outvoted
            // instead of inflating the whole row's mean.
            double[] stripSum = new double[6];
            int[] stripN = new int[6];
            for (int x = 0; x < w; x += stepX) {
                int ca = rowA[x], cb = rowB[x];
                int ga = (int) (0.299 * ((ca >> 16) & 255) + 0.587 * ((ca >> 8) & 255)
                        + 0.114 * (ca & 255));
                int gb = (int) (0.299 * ((cb >> 16) & 255) + 0.587 * ((cb >> 8) & 255)
                        + 0.114 * (cb & 255));
                grayB[gn++] = gb;
                int strip = Math.min(5, (x * 6) / w);
                stripSum[strip] += Math.abs(ga - gb);
                stripN[strip]++;
            }
            double[] stripMean = new double[6];
            int strips = 0;
            for (int j = 0; j < 6; j++) {
                if (stripN[j] > 0) stripMean[strips++] = stripSum[j] / stripN[j];
            }
            java.util.Arrays.sort(stripMean, 0, strips);
            diff[r] = strips > 0 ? stripMean[strips / 2] : 0;
            double det = 0;
            for (int i = 0; i + 1 < gn; i++) det += Math.abs(grayB[i + 1] - grayB[i]);
            detB[r] = gn > 1 ? det / (gn - 1) : 0;
        }
        // Agreement is DETAIL-SCORED (v1.39.13): a splice row is
        // judged only by the rows around it whose incoming frame
        // row carries real horizontal detail (text / sharp
        // edges). Flat rows abstain instead of voting "perfect":
        // they are exactly what fooled the plain windowed mean
        // twice - white chat stretches whose sparse structured
        // rows were out of phase (capture 16), and a transient
        // system volume HUD, flat gray over one frame's seam
        // zone, which inflated the nominal seam past the gate
        // and got its own pixels spliced into the result
        // (capture 17). A detailed nominal seam that already
        // agrees is trusted as-is; moves remain possible for
        // genuinely torn (e.g. playing-video) seams, whose rows
        // are detailed and disagree.
        double nominal = seamScore(diff, detB, overlap, overlap);
        if (nominal == Double.MAX_VALUE || nominal <= 6.0) return overlap;
        // FLAT-CUT PREFERENCE (v1.39.15): when the disagreement
        // around the nominal splice is a phase DRIFT on static
        // content (capture 18: the two frames' rows drift ~2px
        // apart across the seam zone, diffs ramping 0 -> 19 down
        // a text band), NO cut through the band can be clean -
        // the agreement scan below picks the ramp's first row,
        // splitting a text line's glyphs between the two phases
        // (ghosted digits in the result). A cut through rows
        // that are flat in b AND agree between the frames
        // crosses no element at all, so nothing can ghost -
        // prefer the closest such cut within 96 rows above the
        // nominal. The agreement guard (diff < 3) keeps a flat
        // but DIFFERENT region (a gray panel over white rows)
        // from qualifying. Genuinely dynamic content (a playing
        // video) has no flat rows and falls through to the
        // agreement scan unchanged.
        for (int sRow = overlap - 1;
             sRow >= Math.max(8, Math.max(minSeamRow, overlap - 96)); sRow--) {
            if (detB[sRow] < 3.0 && detB[sRow - 1] < 3.0
                    && diff[sRow] < 3.0 && diff[sRow - 1] < 3.0) {
                return sRow;
            }
        }
        int best = overlap;
        double bestScore = Double.MAX_VALUE;
        for (int sRow = Math.max(8, minSeamRow); sRow < overlap; sRow++) {
            double sc = seamScore(diff, detB, overlap, sRow);
            if (sc == Double.MAX_VALUE) continue;
            double cand = sc + 0.01 * (overlap - sRow);
            if (cand < bestScore) {
                bestScore = cand;
                best = sRow;
            }
        }
        if (best < overlap && seamScore(diff, detB, overlap, best) <= 6.0
                && bestScore < nominal * 0.5) {
            return best;
        }
        return overlap;
    }

    // Detail-scored agreement for a splice at row s of b: the
    // trimmed (best 70%) mean of the plain row diffs over the
    // detailed rows (detB >= 6.0) in the +-4 window around the
    // splice row. MAX_VALUE (abstain) when the window holds
    // fewer than 4 detailed rows - nothing there can certify
    // either a tear or a heal.
    private static double seamScore(double[] diff, double[] detB, int overlap, int s) {
        int lo = Math.max(0, s - 5), hi = Math.min(overlap - 1, s + 3);
        double[] vals = new double[hi - lo + 1];
        int cnt = 0;
        for (int k = lo; k <= hi; k++) {
            if (detB[k] >= 6.0) vals[cnt++] = diff[k];
        }
        if (cnt < 4) return Double.MAX_VALUE;
        java.util.Arrays.sort(vals, 0, cnt);
        int keep = Math.max(1, (int) Math.ceil(cnt * 0.7));
        double sum = 0;
        for (int i = 0; i < keep; i++) sum += vals[i];
        return sum / keep;
    }

    // ---- Transient edge-panel cover check (v1.39.17) ------------
    // Detects a transient system panel (the volume HUD is the
    // proven case) that appeared at the frame's right edge
    // between two consecutive grabs: a solid, non-white block
    // anchored at the right edge covering rows that carried
    // ordinary content in the previous frame. Inputs are
    // 320px-wide thumbnails of the full frames; the middle
    // 80% of rows are scanned (the panel lives mid-frame; the
    // status/nav zones are excluded). Calibrated on real
    // captures at this thumbnail scale: panel frames score
    // covered 2.7-22.6% of rows with runs of 2-45 sampled
    // rows (the low end is a panel caught mid-animation, only
    // its leading edge on screen); clean scrolling pairs
    // score <= 1.1% with runs <= 1. A cover means the user
    // pressed a system key mid-capture - CaptureService treats
    // it as a stop (and retakes the frame clean), which also
    // covers the case the volume-change signal cannot see:
    // a volume-down press at minimum volume changes nothing.
    public static boolean panelCovered(Bitmap prevThumb, Bitmap curThumb) {
        if (prevThumb == null || curThumb == null) return false;
        int w = curThumb.getWidth();
        int h = curThumb.getHeight();
        if (prevThumb.getWidth() != w || prevThumb.getHeight() != h) return false;
        if (w < 80 || h < 80) return false;
        int y0 = h / 10, y1 = h - h / 10;
        int covered = 0, total = 0, run = 0, bestRun = 0;
        for (int y = y0; y < y1; y += 2) {
            total++;
            boolean c = panelRowAt(curThumb, y) && !panelRowAt(prevThumb, y);
            if (c) {
                covered++;
                run++;
                if (run > bestRun) bestRun = run;
            } else {
                run = 0;
            }
        }
        return total > 0 && covered * 1000 >= total * 25 && bestRun >= 2;
    }

    // Strong presence (v1.39.22; contiguity gate added
    // v1.39.24): a FULL system panel in this frame - the
    // mid-capture guard's and the final-frame verifier's
    // test. Same wide row scan as panelPresent but with a
    // high fraction gate (>= 8% of rows), a band cap (the
    // longest run of qualifying rows must cover <= 70% of
    // the scanned rows - chat pages' uniform background
    // qualifies on ~100% and is excluded by the cap), and a
    // CONTIGUITY gate: the longest run must reach >= 40
    // sampled rows. Capture 25 taught the last one: clean
    // news-feed frames with large light image areas (a
    // white paper-texture banner) qualify on 9-26% of rows -
    // as high as a real panel's 31-37% - but their runs
    // break up at <= 30 sampled rows, while a real panel is
    // one unbroken block of 53-74. Fraction alone cannot
    // separate texture from panel; contiguity can. Slivers
    // (~4%, runs ~3-5) deliberately stay below every gate
    // here; the delta cover chain handles those.
    public static boolean panelPresentStrong(Bitmap thumb) {
        if (thumb == null) return false;
        int w = thumb.getWidth();
        int h = thumb.getHeight();
        if (w < 80 || h < 80) return false;
        int y0 = h / 10, y1 = h - h / 10;
        int cnt = 0, total = 0, run = 0, bestRun = 0;
        for (int y = y0; y < y1; y += 2) {
            total++;
            if (panelRowWide(thumb, y)) {
                cnt++;
                run++;
                if (run > bestRun) bestRun = run;
            } else {
                run = 0;
            }
        }
        return total > 0 && cnt * 100 >= total * 8
                && bestRun * 100 <= total * 70 && bestRun >= 40;
    }

    // Inset-slab presence (v1.39.32): Honor's volume pill
    // RESTS a few px short of the right edge. On chat pages
    // the fraction/run presence tests are blind (the uniform
    // background qualifies on every row: panelPresent fires
    // on all chat frames, panelPresentStrong on none -
    // capture 34), and the edge-anchored delta misses an
    // inset pill entirely (capture 35's frame 16 was stitched
    // with the pill inside). The remaining signature is
    // geometric: a wide neutral run (panelRowWide sampling)
    // whose RIGHT end stops inset (rightmost sample <= w-12)
    // with both extent ends stable within 6 px, sustained
    // over >= 30 sampled rows (~ the pill's height).
    // Measured on real frames: pill frames chain 45-55
    // (captures 21/34/35), clean frames of every surface
    // <= 13 (capture 25's feed), glancing stop-grabs 21-22.
    // Used only where a false fire costs a retake (the
    // mid-capture guard and the final-frame verifier),
    // never as a stop signal.
    public static boolean panelInsetSlab(Bitmap thumb) {
        if (thumb == null) return false;
        int w = thumb.getWidth();
        int h = thumb.getHeight();
        if (w < 80 || h < 80) return false;
        int y0 = h / 10, y1 = h - h / 10;
        int chain = 0, bestChain = 0, pL = -9999, pR = -9999;
        int[] ext = new int[3];
        for (int y = y0; y < y1; y += 2) {
            wideRunExtent(thumb, y, ext);
            boolean inset = ext[0] >= 4 && ext[2] <= w - 12;
            if (inset) {
                if (chain > 0 && Math.abs(ext[1] - pL) <= 6
                        && Math.abs(ext[2] - pR) <= 6) chain++;
                else chain = 1;
                pL = ext[1];
                pR = ext[2];
                if (chain > bestChain) bestChain = chain;
            } else {
                chain = 0;
            }
        }
        return bestChain >= 30;
    }

    // Loose inset trigger (v1.39.33): capture 36's frame 18
    // caught the pill MID-FADE - translucent, its run extent
    // wobbling by more than the crisp test's 6 px tolerance,
    // so the chain broke into fragments (max 15) and the
    // frame was stitched with the pill's lower half (the
    // "..." button) inside. Counting inset-qualifying rows
    // WITHOUT the extent-stability requirement reads 34 on
    // that frame and 68-88 on crisp pill frames - but 54-67
    // on clean news-feed frames (photo areas), so the count
    // alone cannot trigger anywhere. On CHAT-like pages the
    // wide scan qualifies on >= 80% of rows (the uniform
    // background - the same property that blinds the
    // fraction tests), and there the clean frames read ZERO
    // inset rows (captures 34/35/36, all frames). So: this
    // trigger fires only on chat-like pages, and even then
    // it only buys a same-position temporal probe (see
    // insetRegionChange) - a stable region (a fixed UI
    // element, a photo chat background) keeps its frame.
    public static boolean panelInsetLoose(Bitmap thumb) {
        if (thumb == null) return false;
        int w = thumb.getWidth();
        int h = thumb.getHeight();
        if (w < 80 || h < 80) return false;
        int y0 = h / 10, y1 = h - h / 10;
        int total = 0, qual = 0, inset = 0;
        int[] ext = new int[3];
        for (int y = y0; y < y1; y += 2) {
            total++;
            wideRunExtent(thumb, y, ext);
            if (ext[0] >= 4) {
                qual++;
                if (ext[2] <= w - 12) inset++;
            }
        }
        return total > 0 && qual * 100 >= total * 80 && inset >= 25;
    }

    // Mean absolute luma change between two same-position
    // thumbnails, measured over the rows where CUR carries
    // an inset slab (its wide run ends inset) and over those
    // runs' span. A transient panel fades or slides within
    // a second, so its rows change materially; a fixed
    // element or a photo background is pixel-stable. -1
    // when cur has no inset rows. Threshold in the caller
    // (4.0, the panelBandStability change scale).
    public static float insetRegionChange(Bitmap cur, Bitmap probe) {
        if (cur == null || probe == null) return -1f;
        int w = cur.getWidth();
        int h = cur.getHeight();
        if (w < 80 || h < 80 || probe.getWidth() != w || probe.getHeight() != h) return -1f;
        int y0 = h / 10, y1 = h - h / 10;
        int[] ext = new int[3];
        double sum = 0;
        long n = 0;
        for (int y = y0; y < y1; y += 2) {
            wideRunExtent(cur, y, ext);
            if (ext[0] < 4 || ext[2] > w - 12) continue;
            for (int x = Math.max(0, ext[1]); x <= ext[2]; x += 3) {
                sum += Math.abs(grayOf(cur.getPixel(x, y)) - grayOf(probe.getPixel(x, y)));
                n++;
            }
        }
        return n == 0 ? -1f : (float) (sum / n);
    }

    // Longest neutral mid-gray run in the rightmost quarter
    // of a row (exactly panelRowWide's sampling), reporting
    // its extent: out = {runSamples, leftmostX, rightmostX};
    // run 0 (and -1 extents) when no sample qualifies.
    private static void wideRunExtent(Bitmap b, int y, int[] out) {
        int w = b.getWidth();
        int x0 = w - (w / 4);
        int run = 0, best = 0, bL = -1, bR = -1, rStart = -1, prevLevel = -1000;
        for (int x = w - 3; x >= x0; x -= 3) {
            int p = b.getPixel(x, y);
            int r = (p >> 16) & 255, g = (p >> 8) & 255, bl = p & 255;
            int level = (int) (0.299 * r + 0.587 * g + 0.114 * bl);
            boolean neutral = Math.abs(r - g) <= 12 && Math.abs(g - bl) <= 12;
            boolean inLevel = level >= 170 && level <= 248;
            if (neutral && inLevel && Math.abs(level - prevLevel) <= 10) {
                if (run == 0) rStart = x;
                run++;
            } else if (neutral && inLevel) {
                run = 1;
                rStart = x;
            } else {
                run = 0;
            }
            if (run > best) {
                best = run;
                bL = x;
                bR = rStart;
            }
            prevLevel = (neutral && inLevel) ? level : -1000;
        }
        out[0] = best;
        out[1] = bL;
        out[2] = bR;
    }

    // Absolute presence form of the cover check (v1.39.18,
    // row test replaced in v1.39.19): does THIS frame contain
    // a system-panel block? Used to verify a retaken final
    // frame before it is stitched - the timed wait alone is
    // not proof the panel expired.
    //
    // The two panel tests are deliberately different. The
    // DELTA stop detector (panelCovered) is edge-anchored and
    // conservative: a false positive there STOPS a capture.
    // This PRESENCE test serves a stopping capture, where a
    // false positive only spends one bounded retake retry, so
    // it is the sensitive form: the longest neutral mid-gray
    // run anywhere in the rightmost quarter of the row. The
    // wide scan is required, not optional: Honor's settled
    // volume pill FLOATS a few px short of the right edge
    // (captures 20/21), so the edge-anchored row test reads
    // ZERO qualifying rows on frames containing the full
    // panel (capture 21, device-verified miss: the verified
    // retake was accepted with the panel in it), while this
    // scan reads 93/261 rows with a run of 62 on the same
    // frame. Calibrated at thumbnail scale - panels: cap17
    // f10 = 82 rows, cap20 f9 = 96, cap21 f10 = 93, the two
    // mid-animation slivers = 11 each; clean list frames give
    // <= 9 rows but never 2 in a row, so the run gate is what
    // separates them.
    public static boolean panelPresent(Bitmap thumb) {
        if (thumb == null) return false;
        int w = thumb.getWidth();
        int h = thumb.getHeight();
        if (w < 80 || h < 80) return false;
        int y0 = h / 10, y1 = h - h / 10;
        int cnt = 0, total = 0, run = 0, bestRun = 0;
        for (int y = y0; y < y1; y += 2) {
            total++;
            if (panelRowWide(thumb, y)) {
                cnt++;
                run++;
                if (run > bestRun) bestRun = run;
            } else {
                run = 0;
            }
        }
        return total > 0 && cnt * 1000 >= total * 15 && bestRun >= 2;
    }

    // One row of the wide presence test: the longest run of
    // near-equal NEUTRAL mid-gray samples (level 170-248,
    // channels within 12 of each other, consecutive samples
    // within 10) in the rightmost quarter of the row; a run
    // of >= 4 samples (~12 thumbnail px) qualifies. Neutral
    // excludes coloured content; the level band excludes
    // white backgrounds and dark text.
    private static boolean panelRowWide(Bitmap b, int y) {
        int w = b.getWidth();
        int x0 = w - (w / 4);
        int run = 0, best = 0, prevLevel = -1000;
        for (int x = w - 3; x >= x0; x -= 3) {
            int p = b.getPixel(x, y);
            int r = (p >> 16) & 255, g = (p >> 8) & 255, bl = p & 255;
            int level = (int) (0.299 * r + 0.587 * g + 0.114 * bl);
            boolean neutral = Math.abs(r - g) <= 12 && Math.abs(g - bl) <= 12;
            boolean inLevel = level >= 170 && level <= 248;
            if (neutral && inLevel && Math.abs(level - prevLevel) <= 10) run++;
            else if (neutral && inLevel) run = 1;
            else run = 0;
            if (run > best) best = run;
            prevLevel = (neutral && inLevel) ? level : -1000;
        }
        return best >= 4;
    }

    // Covered-band statistics of the delta cover check:
    // {coveredRowCount, scannedRows, meanEdgeLevel} over the
    // rows panelCovered() counts (edge row test, newly
    // covered). Used by the capture loop's suspect
    // confirmation: a real volume pill's band sits at system
    // gray levels (measured means 233-245 across captures
    // 17-19), while the photo gradient that false-fired on a
    // news feed in capture 22 measured 224.7.
    public static float[] panelCoverBand(Bitmap prev, Bitmap cur) {
        if (prev == null || cur == null) return null;
        int w = cur.getWidth();
        int h = cur.getHeight();
        if (w < 80 || h < 80 || prev.getWidth() != w || prev.getHeight() != h) return null;
        int y0 = h / 10, y1 = h - h / 10;
        int cnt = 0, total = 0;
        double lvl = 0;
        for (int y = y0; y < y1; y += 2) {
            total++;
            if (panelRowAt(cur, y) && !panelRowAt(prev, y)) {
                cnt++;
                lvl += grayOf(cur.getPixel(w - 3, y));
            }
        }
        return new float[]{cnt, total, cnt > 0 ? (float) (lvl / cnt) : -1f};
    }

    // Stability of the suspect frame's wide panel band in a
    // later NO-SCROLL probe of the same screen:
    // {bandRows, persistFraction, meanChange}. A system panel
    // is transient - within a second or two it fades (its band
    // pixels drift toward the content) or expires (the band
    // collapses) - while page content is pixel-stable:
    // persistFraction near 1 and meanChange near 0. This is
    // the temporal half of the suspect confirmation; it is
    // what separates the volume pill from a static light photo
    // region, which no single-frame test could tell apart
    // (capture 22's photo band twins the sliver panels on
    // every static statistic).
    public static double[] panelBandStability(Bitmap suspect, Bitmap probe) {
        if (suspect == null || probe == null) return new double[]{0, 1, 0};
        int w = suspect.getWidth();
        int h = suspect.getHeight();
        if (w < 80 || h < 80 || probe.getWidth() != w || probe.getHeight() != h) {
            return new double[]{0, 1, 0};
        }
        int y0 = h / 10, y1 = h - h / 10;
        int x0 = w - (w / 4);
        int band = 0, persist = 0;
        double changeSum = 0;
        int samples = 0;
        for (int y = y0; y < y1; y += 2) {
            if (!panelRowWide(suspect, y)) continue;
            band++;
            if (panelRowWide(probe, y)) persist++;
            for (int x = w - 4; x >= x0; x -= 4) {
                changeSum += Math.abs(grayOf(suspect.getPixel(x, y)) - grayOf(probe.getPixel(x, y)));
                samples++;
            }
        }
        if (band == 0) return new double[]{0, 1, 0};
        return new double[]{band, (double) persist / band,
                samples > 0 ? changeSum / samples : 0};
    }

    // A solid block anchored at the right edge on this row: the
    // edge sample is mid-gray (170-248 - not white content
    // background, not dark text) and at least 4 samples (~12
    // thumb px) leftward stay within 10 gray levels of it.
    private static boolean panelRowAt(Bitmap b, int y) {
        int w = b.getWidth();
        int edge = grayOf(b.getPixel(w - 3, y));
        if (edge < 170 || edge > 248) return false;
        int run = 0;
        for (int x = w - 3; x > w - 68; x -= 3) {
            if (Math.abs(grayOf(b.getPixel(x, y)) - edge) <= 10) run++;
            else break;
        }
        return run >= 4;
    }

    // ---- Split (two-segment) stitching ------------------------------
    // A page can reflow MID-CAPTURE: a video player finishes loading
    // and expands, a lazy image pops in, an ad slot opens. Rows below
    // the reflow point then sit a few dozen px lower (or higher) than
    // in the previous frame, and NO single offset aligns both sides -
    // a one-offset join either drops or duplicates a strip exactly
    // at the reflow point (seen on a real device at a playing video:
    // one truncated strip in an otherwise perfect capture). The
    // consensus bands already measure per-band offsets; when the
    // upper bands vote one tight offset and the lower bands vote
    // another, stitch the frame in two segments and hide the seam
    // inside the boundary band (usually the dynamic element itself).

    public static class SplitInfo {
        public final int overlapUpper;
        public final int splitRow;
        public final int overlapLower;

        public SplitInfo(int overlapUpper, int splitRow, int overlapLower) {
            this.overlapUpper = overlapUpper;
            this.splitRow = splitRow;
            this.overlapLower = overlapLower;
        }
    }

    /** Two-segment overlap search; null when no trustworthy split exists. */
    public static SplitInfo findSplitOverlap(Bitmap a, Bitmap b,
            double expectedOverlapRatio, double topSkipRatio, double bottomSkipRatio) {
        if (a == null || b == null) return null;
        Bitmap bb = b;
        boolean scaledCopy = false;
        if (bb.getWidth() != a.getWidth()) {
            bb = Bitmap.createScaledBitmap(b, a.getWidth(),
                    (int) Math.round((double) b.getHeight() * a.getWidth() / b.getWidth()), true);
            scaledCopy = true;
        }
        SplitInfo out = null;
        try {
            out = findSplitOverlapInner(a, bb, expectedOverlapRatio, topSkipRatio, bottomSkipRatio);
        } finally {
            if (scaledCopy) bb.recycle();
        }
        return out;
    }

    private static SplitInfo findSplitOverlapInner(Bitmap a, Bitmap bb,
            double expectedOverlapRatio, double topSkipRatio, double bottomSkipRatio) {
        int w = a.getWidth();
        int hB = bb.getHeight();
        int expected = (int) Math.round(hB * expectedOverlapRatio);
        int lo = Math.max((int) (hB * 0.05), expected - (int) (hB * 0.40));
        int hi = Math.min((int) (hB * 0.95), expected + (int) (hB * 0.40));
        if (hi <= lo) return null;
        int[] grayA = toGraySmall(a, MATCH_WIDTH);
        int[] grayB = toGraySmall(bb, MATCH_WIDTH);
        int sw = MATCH_WIDTH;
        int shA = (int) Math.round((double) a.getHeight() * sw / a.getWidth());
        int shB = (int) Math.round((double) bb.getHeight() * sw / bb.getWidth());
        int topSkip = (int) (shB * topSkipRatio);
        int bottomSkip = (int) (shB * bottomSkipRatio);
        int loS = Math.max(topSkip + 4, (int) Math.round(lo * (double) sw / w));
        int hiS = Math.min(shB - 2, (int) Math.round(hi * (double) sw / w));
        if (hiS <= loS) return null;
        int regionStart = Math.max(topSkip, (int) (shB * 0.08));
        int regionEnd = Math.min(shB - bottomSkip - 2, (int) (shB * 0.97));
        int bandCount = 8;
        int bandH = (regionEnd - regionStart) / bandCount;
        if (bandH < 6) return null;
        int[] votes = new int[bandCount];
        double[] scores = new double[bandCount];
        int[] bandTops = new int[bandCount];
        for (int k = 0; k < bandCount; k++) {
            int b0 = regionStart + k * bandH;
            int b1 = (k == bandCount - 1) ? regionEnd : Math.min(regionEnd, b0 + bandH);
            bandTops[k] = b0;
            votes[k] = -1;
            scores[k] = Double.MAX_VALUE;
            if (b1 - b0 < 4) continue;
            int pLo = Math.max(0, shA - hiS + b0);
            int pHi = Math.min(shA - (b1 - b0), shA - loS + b0);
            if (pHi < pLo) continue;
            int bestP = -1;
            double best = Double.MAX_VALUE;
            for (int p = pLo; p <= pHi; p += 2) {
                double sc = scoreBand(grayA, shA, grayB, sw, p, b0, b1 - b0);
                if (sc < best) { best = sc; bestP = p; }
            }
            if (bestP >= 0) {
                int fLo = Math.max(pLo, bestP - 2);
                int fHi = Math.min(pHi, bestP + 2);
                for (int p = fLo; p <= fHi; p++) {
                    double sc = scoreBand(grayA, shA, grayB, sw, p, b0, b1 - b0);
                    if (sc < best) { best = sc; bestP = p; }
                }
            }
            if (bestP >= 0) {
                votes[k] = shA + b0 - bestP;
                scores[k] = best;
            }
        }
        // Boundary search: upper bands vote one tight offset, lower
        // bands another. Strict gates - a false split is worse than
        // a single-offset join.
        int bestK = -1;
        double bestSep = 0;
        double meanUBest = 0, meanLBest = 0;
        for (int k = 2; k <= bandCount - 2; k++) {
            double[] u = clusterStats(votes, scores, 0, k);
            double[] l = clusterStats(votes, scores, k, bandCount);
            if (u == null || l == null) continue;
            double sepSmall = Math.abs(u[0] - l[0]);
            double sepPx = sepSmall * w / sw;
            if (sepSmall < 4 || sepPx < 16) continue;
            if (sepPx > bestSep) {
                bestSep = sepPx;
                bestK = k;
                meanUBest = u[0];
                meanLBest = l[0];
            }
        }
        if (bestK < 0) return null;
        int oU = (int) Math.round(meanUBest * w / sw);
        int oL = (int) Math.round(meanLBest * w / sw);
        int splitSmall = bandTops[bestK];
        int splitRow = (int) Math.round(splitSmall * (double) w / sw);
        if (oU < 0 || oL < 0 || oU >= hB || oL >= hB) return null;
        if (splitRow < 40 || splitRow > hB - 40) return null;
        if (!scrollPlausible(hB, expected, oU)) return null;
        // The lower segment's implied scroll must ALSO be plausible
        // and the shift itself bounded: a reflow moves content by a
        // few dozen px, while an alias on repetitive content sits a
        // whole card period away and implies an absurd scroll (on
        // the bench an alias at overlap 811 vs expected ~480 -
        // implied scroll 113 after a ~444px swipe - passed segment
        // verification on near-identical cards before this gate).
        if (!scrollPlausible(hB, expected, oL)) return null;
        if (bestSep > 150) return null;
        // Segment verification: each side must align cleanly under
        // its own offset (clipped scores, so a dynamic region inside
        // a segment cannot dominate the verdict).
        int oUS = (int) Math.round(oU * (double) sw / w);
        int oLS = (int) Math.round(oL * (double) sw / w);
        // Verify only rows that actually overlap the canvas on
        // each side of the split (frame rows beyond the segment's
        // overlap are new content with no counterpart to score),
        // and require a minimum of verified rows as evidence.
        int verU = Math.min(splitSmall, oUS) - topSkip;
        int verL = Math.min(regionEnd, oLS) - splitSmall;
        if (verU < 24 || verL < 24) return null;
        double segU = regionScore(grayA, shA, grayB, sw, oUS, topSkip, Math.min(splitSmall, oUS));
        double segL = regionScore(grayA, shA, grayB, sw, oLS, splitSmall, Math.min(regionEnd, oLS));
        if (segU >= 14.0 || segL >= 14.0) return null;
        oU = refineInRegion(a, bb, oU, 0, splitRow);
        oL = refineInRegion(a, bb, oL, splitRow, hB - (int) (hB * bottomSkipRatio));
        return new SplitInfo(oU, splitRow, oL);
    }

    // {mean, } of the trustworthy votes (score < 14) in bands
    // [from, to), or null when fewer than 2 vote or they disagree
    // by more than 3 small rows.
    private static double[] clusterStats(int[] votes, double[] scores, int from, int to) {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        long sum = 0;
        int n = 0;
        for (int i = from; i < to; i++) {
            if (votes[i] < 0 || scores[i] >= 14.0) continue;
            if (votes[i] < min) min = votes[i];
            if (votes[i] > max) max = votes[i];
            sum += votes[i];
            n++;
        }
        if (n < 2 || max - min > 3) return null;
        return new double[]{(double) sum / n};
    }

    private static double regionScore(int[] grayA, int shA, int[] grayB, int sw,
            int oSmall, int r0, int r1) {
        double sum = 0;
        int n = 0;
        for (int y = r0; y < r1; y += 8) {
            int rows = Math.min(8, r1 - y);
            if (rows < 3) break;
            int p = shA - oSmall + y;
            if (p < 0 || p + rows > shA) return Double.MAX_VALUE;
            sum += scoreBand(grayA, shA, grayB, sw, p, y, rows);
            n++;
        }
        return n == 0 ? Double.MAX_VALUE : sum / n;
    }

    // refineVerticalOverlap restricted to strips inside
    // [regionTop, regionBottom) of b (and inside the overlap).
    private static int refineInRegion(Bitmap a, Bitmap b, int overlap, int regionTop, int regionBottom) {
        int w = a.getWidth();
        int hA = a.getHeight();
        int hB = b.getHeight();
        int lo = Math.max(regionTop, 0);
        int hi = Math.min(regionBottom, overlap);
        int stripH = 48;
        if (hi - lo < stripH + 16) return overlap;
        int[] candY = {
                lo + (hi - lo) / 4,
                lo + (hi - lo) / 2,
                lo + 3 * (hi - lo) / 4,
        };
        int bestDelta = 0;
        double bestStripScore = Double.MAX_VALUE;
        boolean found = false;
        for (int yB : candY) {
            if (yB + stripH > hi) yB = hi - stripH;
            if (yB < lo) continue;
            int yA = hA - overlap + yB;
            if (yA - 8 < 0 || yA + stripH + 8 > hA) continue;
            int[] grayA = grayRegion(a, yA - 8, stripH + 16);
            int[] grayB = grayRegion(b, yB, stripH);
            if (grayA == null || grayB == null) continue;
            if (stripDetail(grayB, w, stripH) < 2.0) continue;
            double candBest = stripScore(grayA, grayB, w, stripH, 0);
            int candBestDelta = 0;
            for (int d = -8; d <= 8; d++) {
                if (d == 0) continue;
                double sc = stripScore(grayA, grayB, w, stripH, d);
                if (sc < candBest - 0.01 || (Math.abs(sc - candBest) <= 0.01 && Math.abs(d) < Math.abs(candBestDelta))) {
                    candBest = sc;
                    candBestDelta = d;
                }
            }
            if (!found || candBest < bestStripScore) {
                found = true;
                bestStripScore = candBest;
                bestDelta = candBestDelta;
            }
        }
        if (!found || bestStripScore > 25.0) return overlap;
        int refined = overlap - bestDelta;
        if (refined < Math.max(0, overlap - 16)) refined = Math.max(0, overlap - 16);
        if (refined > Math.min(hB - 1, overlap + 16)) refined = Math.min(hB - 1, overlap + 16);
        return refined;
    }

    /** Stitch b below a in two segments per the split; null on bad geometry. */
    public static Bitmap stitchVerticalSplit(Bitmap a, Bitmap b, SplitInfo sp) {
        if (a == null || b == null || sp == null) return null;
        if (a.getWidth() != b.getWidth()) return null;
        int w = a.getWidth();
        int hA = a.getHeight();
        int hB = b.getHeight();
        int oU = sp.overlapUpper, oL = sp.overlapLower, split = sp.splitRow;
        int newH = hA + (hB - oL);
        if (newH <= hA || split <= 0 || split >= hB) return null;
        Bitmap out = Bitmap.createBitmap(w, newH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawBitmap(a, 0, 0, null);
        Bitmap part = Bitmap.createBitmap(b, 0, 0, w, split);
        canvas.drawBitmap(part, 0, hA - oU, null);
        part.recycle();
        if (oL < oU) {
            // Expansion: the rows the page gained sit between the
            // upper segment's end and the lower segment's start;
            // fill them from b's rows just above the split (they
            // belong to the dynamic boundary band).
            int gap = oU - oL;
            int srcY = Math.max(0, split - gap);
            if (split > srcY) {
                Bitmap filler = Bitmap.createBitmap(b, 0, srcY, w, split - srcY);
                canvas.drawBitmap(filler, 0, hA - oL + srcY, null);
                filler.recycle();
            }
        }
        Bitmap tail = Bitmap.createBitmap(b, 0, split, w, hB - split);
        canvas.drawBitmap(tail, 0, hA - oL + split, null);
        tail.recycle();
        return out;
    }

    /**
     * Try a split stitch for this pair; returns a Result whose
     * overlap is the LOWER segment's overlap (the appended height
     * follows the post-reflow layout), or null when no trustworthy
     * split exists and the caller should use the normal path.
     */
    public static Result trySplitStitch(Bitmap a, Bitmap b, double expectedOverlapRatio,
            double topSkipRatio, double bottomSkipRatio) {
        SplitInfo sp = findSplitOverlap(a, b, expectedOverlapRatio, topSkipRatio, bottomSkipRatio);
        if (sp == null) return null;
        Bitmap out = stitchVerticalSplit(a, b, sp);
        if (out == null) return null;
        return new Result(out, sp.overlapLower, true);
    }

    /** Stitch b below a (vertical). expectedOverlapRatio in (0,1). */
    public static Result stitchVertical(Bitmap a, Bitmap b, double expectedOverlapRatio, double topSkipRatio) {
        OverlapInfo oi = findVerticalOverlap(a, b, expectedOverlapRatio, topSkipRatio);
        return stitchVerticalWithOverlap(a, b, oi.overlap, oi.matched);
    }

    /**
     * Small screen rectangles (x, y, w, h) whose pixels are (nearly)
     * identical in a and b at the SAME screen position, restricted to
     * rows [yStart, yEnd). These are floating on-screen elements
     * (a bottom dock, a round refresh button, ...) or coincidentally
     * unchanged content. CaptureService uses them for back-filling:
     * content hidden behind such an element in one frame is visible
     * in the next frame at a shifted position.
     */
    public static java.util.List<int[]> staticCells(Bitmap a, Bitmap b, int yStart, int yEnd, int cell) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        if (a == null || b == null) return out;
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return out;
        int w = a.getWidth();
        int h = a.getHeight();
        int ys = Math.max(0, yStart);
        int ye = Math.min(h, yEnd);
        int step = Math.max(2, cell / 4);
        for (int y0 = (ys / cell) * cell; y0 < ye; y0 += cell) {
            int ch = Math.min(cell, h - y0);
            if (y0 + ch <= ys) continue;
            for (int x0 = 0; x0 < w; x0 += cell) {
                int cw = Math.min(cell, w - x0);
                long sum = 0;
                int count = 0;
                for (int y = y0; y < y0 + ch; y += step) {
                    for (int x = x0; x < x0 + cw; x += step) {
                        int pa = a.getPixel(x, y);
                        int pb = b.getPixel(x, y);
                        sum += Math.abs(((pa >> 16) & 0xff) - ((pb >> 16) & 0xff));
                        sum += Math.abs(((pa >> 8) & 0xff) - ((pb >> 8) & 0xff));
                        sum += Math.abs((pa & 0xff) - (pb & 0xff));
                        count += 3;
                    }
                }
                double mean = count == 0 ? Double.MAX_VALUE : (double) sum / count;
                if (mean < 8.0) {
                    out.add(new int[]{x0, y0, cw, ch});
                }
            }
        }
        return out;
    }

    /** Stitch b to the right of a (horizontal), same append-only idea. */
    public static Result stitchHorizontal(Bitmap a, Bitmap b, double expectedOverlapRatio) {
        if (a == null) return new Result(b, 0, false);
        if (b == null) return new Result(a, 0, false);
        Bitmap bb = b;
        boolean scaledCopy = false;
        if (bb.getHeight() != a.getHeight()) {
            bb = Bitmap.createScaledBitmap(b,
                    (int) Math.round((double) b.getWidth() * a.getHeight() / b.getHeight()), a.getHeight(), true);
            scaledCopy = true;
        }
        int h = a.getHeight();
        int wA = a.getWidth();
        int wB = bb.getWidth();
        int expected = (int) Math.round(wB * expectedOverlapRatio);
        int overlap = Math.max(0, Math.min(wB - 1, expected));
        int newW = wA + wB - overlap;
        if (newW > 40000) {
            if (scaledCopy) bb.recycle();
            return new Result(Bitmap.createScaledBitmap(a, wA / 2, h / 2, true), overlap, false);
        }
        Bitmap out = Bitmap.createBitmap(newW, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawBitmap(a, 0, 0, null);
        Bitmap part = Bitmap.createBitmap(bb, overlap, 0, wB - overlap, h);
        canvas.drawBitmap(part, wA, 0, null);
        part.recycle();
        if (scaledCopy) bb.recycle();
        return new Result(out, overlap, true);
    }

    // Per-sample differences are CLIPPED at 48 in the matching scores.
    // A changing region (playing video, GIF) produces huge diffs on a
    // minority of samples; unclipped, it inflates the score of the TRUE
    // offset so much that on repetitive content a wrong (aliased)
    // offset can win the argmin - measured on the test bench, and the
    // mechanism behind content bands vanishing from the real news
    // feed. Clipping bounds any minority region's influence while the
    // FRACTION of differing samples (which is what actually separates
    // right from wrong offsets) keeps discriminating.
    private static int clipDiff(int d) { return d > 48 ? 48 : d; }

    /** Mean clipped abs diff between band rows of B and rows of A starting at aStart. */
    private static double scoreBand(int[] grayA, int shA, int[] grayB, int sw, int aStart, int bStart, int rows) {
        long sum = 0;
        long count = 0;
        for (int y = 0; y < rows; y++) {
            int yA = aStart + y;
            int yB = bStart + y;
            if (yA < 0 || yA >= shA) return Double.MAX_VALUE;
            int rowA = yA * sw;
            int rowB = yB * sw;
            for (int x = 0; x < sw; x += 2) {
                int idxA = rowA + x;
                int idxB = rowB + x;
                if (idxA >= grayA.length || idxB >= grayB.length) break;
                sum += clipDiff(Math.abs(grayB[idxB] - grayA[idxA]));
                count++;
            }
        }
        return count == 0 ? Double.MAX_VALUE : (double) sum / count;
    }

    // Row-trimmed band score (v1.28): per-row clipped means inside
    // the band, averaged over the best 60% of rows (histogram over
    // the 0..48 clipped range - no sort, no allocation). A band
    // that mixes a static title with a playing video used to score
    // its plain mean, so at the true offset the video poisoned the
    // band's vote away; on Hayes's real capture one join's only
    // sharp static feature was a two-line title inside a band that
    // was 2/3 dynamic video, and every whole-band statistic missed
    // the true offset by ~380px (the card got duplicated). With
    // per-row trimming the static rows inside the band decide.
    private static double trimmedBandScore(int[] grayA, int shA, int[] grayB, int sw,
                                           int aStart, int bStart, int rows) {
        if (rows <= 0) return Double.MAX_VALUE;
        int[] hist = new int[49];
        int counted = 0;
        for (int y = 0; y < rows; y++) {
            int yA = aStart + y;
            int yB = bStart + y;
            if (yA < 0 || yA >= shA) return Double.MAX_VALUE;
            int rowA = yA * sw;
            int rowB = yB * sw;
            long sum = 0;
            int count = 0;
            for (int x = 0; x < sw; x += 2) {
                int idxA = rowA + x;
                int idxB = rowB + x;
                if (idxA >= grayA.length || idxB >= grayB.length) break;
                sum += clipDiff(Math.abs(grayB[idxB] - grayA[idxA]));
                count++;
            }
            if (count == 0) continue;
            int bucket = (int) (sum / count);
            if (bucket > 48) bucket = 48;
            hist[bucket]++;
            counted++;
        }
        if (counted == 0) return Double.MAX_VALUE;
        int keep = Math.max(1, (int) Math.ceil(counted * 0.60));
        long acc = 0;
        int taken = 0;
        for (int bIdx = 0; bIdx <= 48 && taken < keep; bIdx++) {
            int take = Math.min(hist[bIdx], keep - taken);
            acc += (long) take * bIdx;
            taken += take;
        }
        return (double) acc / keep;
    }

    private static double scoreOverlap(int[] grayA, int shA, int[] grayB, int shB, int sw, int overlapSmall, int topSkip) {
        // Compare B[topSkip .. overlapSmall] with the tail of A that it
        // would cover, but score ROBUSTLY: split the overlap into
        // horizontal bands, score each band (clipped mean, every row,
        // every 2nd column at 320px width), and take the MEDIAN band
        // score. A playing video or re-laid-out region can poison a
        // few bands completely; with a plain mean over the whole
        // overlap it inflated the TRUE offset's score past the matched
        // threshold (test bench: the join crossing a 600px video band
        // came back "unmatched" and fell back to the gesture estimate,
        // losing 7px of content and shifting everything after it).
        // The median ignores any minority of poisoned bands, while
        // genuinely wrong offsets score badly in MOST bands.
        int totalRows = overlapSmall - topSkip;
        if (totalRows <= 0) return Double.MAX_VALUE;
        int startA = shA - overlapSmall + topSkip;
        if (startA < 0) return Double.MAX_VALUE;
        int bands = Math.max(1, Math.min(8, totalRows / 16));
        int bandRows = Math.max(1, totalRows / bands);
        double[] bandScores = new double[bands];
        for (int k = 0; k < bands; k++) {
            int yFrom = topSkip + k * bandRows;
            int yTo = (k == bands - 1) ? overlapSmall : Math.min(overlapSmall, yFrom + bandRows);
            long sum = 0;
            long count = 0;
            for (int y = yFrom; y < yTo; y++) {
                int yA = startA + (y - topSkip);
                if (yA < 0 || yA >= shA) continue;
                int rowB = y * sw;
                int rowA = yA * sw;
                for (int x = 0; x < sw; x += 2) {
                    int idxB = rowB + x;
                    int idxA = rowA + x;
                    if (idxB >= grayB.length || idxA >= grayA.length) break;
                    sum += clipDiff(Math.abs(grayB[idxB] - grayA[idxA]));
                    count++;
                }
            }
            bandScores[k] = count == 0 ? Double.MAX_VALUE : (double) sum / count;
        }
        java.util.Arrays.sort(bandScores);
        return bandScores[bands / 2];
    }

    private static int[] toGraySmall(Bitmap bmp, int targetWidth) {
        int sw = targetWidth;
        int sh = Math.max(1, (int) Math.round((double) bmp.getHeight() * sw / bmp.getWidth()));
        Bitmap small = Bitmap.createScaledBitmap(bmp, sw, sh, true);
        int[] pixels = new int[sw * sh];
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh);
        int[] gray = new int[sw * sh];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int r = (p >> 16) & 0xff;
            int g = (p >> 8) & 0xff;
            int b = p & 0xff;
            gray[i] = (r * 30 + g * 59 + b * 11) / 100;
        }
        if (small != bmp) small.recycle();
        return gray;
    }

    /** Mean absolute difference between two bitmaps (downscaled), 0 = identical. */
    public static double frameDifference(Bitmap a, Bitmap b) {
        if (a == null || b == null) return Double.MAX_VALUE;
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return Double.MAX_VALUE;
        int[] ga = toGraySmall(a, 160);
        int[] gb = toGraySmall(b, 160);
        if (ga.length != gb.length) return Double.MAX_VALUE;
        long sum = 0;
        for (int i = 0; i < ga.length; i += 4) {
            sum += Math.abs(ga[i] - gb[i]);
        }
        return (double) sum / Math.max(1, ga.length / 4);
    }

    /**
     * Median of the per-row mean differences at zero shift.
     * Scrolling moves most rows; an in-place change (a list
     * re-sorting itself, a clock ticking, a badge updating) moves
     * few. frameDifference's global mean cannot tell the two
     * apart - a reordered third of the screen outweighs the
     * identical two thirds - so movement checks use this: a
     * median near zero means the majority of the screen is
     * pixel-identical at zero shift, i.e. the page did not
     * scroll, however busy the changed minority looks.
     */
    public static double rowMedianDifference(Bitmap a, Bitmap b) {
        if (a == null || b == null) return Double.MAX_VALUE;
        int w = a.getWidth(), h = a.getHeight();
        if (w != b.getWidth() || h != b.getHeight()) return Double.MAX_VALUE;
        int rowStep = Math.max(1, h / 120);
        int colStep = Math.max(1, w / 48);
        double[] rows = new double[Math.max(1, h / rowStep)];
        int n = 0;
        for (int y = 0; y < h && n < rows.length; y += rowStep) {
            long sum = 0;
            int count = 0;
            for (int x = 0; x < w; x += colStep) {
                int pa = a.getPixel(x, y), pb = b.getPixel(x, y);
                sum += Math.abs(((pa >> 16) & 0xff) - ((pb >> 16) & 0xff))
                     + Math.abs(((pa >> 8) & 0xff) - ((pb >> 8) & 0xff))
                     + Math.abs((pa & 0xff) - (pb & 0xff));
                count += 3;
            }
            rows[n++] = (double) sum / Math.max(1, count);
        }
        java.util.Arrays.sort(rows, 0, n);
        return n == 0 ? Double.MAX_VALUE : rows[n / 2];
    }

    /** Mean absolute difference of one row between two same-size bitmaps, sampled. */
    private static double rowDifference(Bitmap a, Bitmap b, int y) {
        int w = a.getWidth();
        int step = Math.max(1, w / 32);
        long sum = 0;
        int count = 0;
        for (int x = 0; x < w; x += step) {
            int pa = a.getPixel(x, y);
            int pb = b.getPixel(x, y);
            sum += Math.abs(((pa >> 16) & 0xff) - ((pb >> 16) & 0xff));
            sum += Math.abs(((pa >> 8) & 0xff) - ((pb >> 8) & 0xff));
            sum += Math.abs((pa & 0xff) - (pb & 0xff));
            count += 3;
        }
        return count == 0 ? Double.MAX_VALUE : (double) sum / count;
    }

    /**
     * Height of the static top bar shared by two consecutive frames:
     * rows, from the top, that are (nearly) pixel-identical in both.
     * These are app title / toolbar rows that do not scroll and must
     * not be stitched as content. Capped at maxH; 0 when none.
     */
    public static int staticTopHeight(Bitmap a, Bitmap b, int maxH) {
        if (a == null || b == null) return 0;
        int h = Math.min(maxH, Math.min(a.getHeight(), b.getHeight()));
        int y = 0;
        while (y < h && rowDifference(a, b, y) < 6.0) y++;
        // Require a real bar, not a couple of coincidentally equal rows.
        if (y < 16) return 0;

        // Fixed top bars are not always one uninterrupted block: a
        // search hint can rotate inside the bar while the tabs below
        // stay still. Do not stop at the first differing row. Allow
        // short gaps, require a genuinely tall static band (>=64 px),
        // and return the row where that band ends (the content starts).
        int barMin = Math.min(240, Math.max(96, h / 4));
        int scan = y;
        int candidateEnd = y;
        int run = 0;
        int gap = 0;
        int bestRun = 0;
        boolean reachedEnd = false;
        while (scan < h) {
            if (rowDifference(a, b, scan) < 6.0) {
                run++;
                if (run >= 64) gap = 0;
            } else {
                if (run >= 64) {
                    if (run <= bestRun) run = bestRun;
                    gap = 1;
                    candidateEnd = scan;
                    bestRun = run;
                } else {
                    int total = gap + run;
                    if (total == 0) candidateEnd = scan;
                    gap = total + 1;
                }
                if (gap > barMin) {
                    reachedEnd = true;
                    break;
                }
                run = 0;
            }
            scan++;
        }
        if (!reachedEnd && scan >= h) candidateEnd = -1;
        if (candidateEnd > y + 32 && bestRun >= 64) return candidateEnd;
        return y;
    }

    /** Same as staticTopHeight, for a static bottom bar (input box etc.). */
    public static int staticBottomHeight(Bitmap a, Bitmap b, int maxH) {
        if (a == null || b == null) return 0;
        int ha = a.getHeight(), hb = b.getHeight();
        int limit = Math.min(maxH, Math.min(ha, hb));
        int n = 0;
        while (n < limit && rowDifference(a, b, ha - 1 - n) < 6.0) n++;
        return n >= 16 ? n : 0;
    }
}
