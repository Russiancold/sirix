/**
 * Copyright (c) 2011, University of Konstanz, Distributed Systems Group All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met: * Redistributions of source code must retain the
 * above copyright notice, this list of conditions and the following disclaimer. * Redistributions
 * in binary form must reproduce the above copyright notice, this list of conditions and the
 * following disclaimer in the documentation and/or other materials provided with the distribution.
 * * Neither the name of the University of Konstanz nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.sirix.diff;

import static com.google.common.base.Preconditions.checkNotNull;
import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.xml.namespace.QName;
import org.sirix.access.HashKind;
import org.sirix.api.Axis;
import org.sirix.api.XdmNodeReadTrx;
import org.sirix.axis.DescendantAxis;
import org.sirix.axis.IncludeSelf;
import org.sirix.diff.DiffFactory.Builder;
import org.sirix.diff.DiffFactory.DiffOptimized;
import org.sirix.diff.DiffFactory.DiffType;
import org.sirix.exception.SirixException;
import org.sirix.node.Kind;

/**
 * Abstract diff class which implements common functionality.
 *
 * @author Johannes Lichtenberger, University of Konstanz
 *
 */
@Nonnull
abstract class AbstractDiff extends AbstractDiffObservable {

  /** Determines transaction movement. */
  private enum Move {
    /** To the following node (next node in the following-axis). */
    FOLLOWING,

    /** Next node in document order. */
    DOCUMENT_ORDER
  }

  /** Determines the current revision. */
  private enum Revision {
    /** Old revision. */
    OLD,

    /** New revision. */
    NEW;
  }

  /**
   * Kind of hash method.
   *
   * @see HashKind
   */
  private HashKind mHashKind;

  /**
   * Kind of difference.
   *
   * @see DiffType
   */
  private DiffType mDiff;

  /** Diff kind. */
  private DiffOptimized mDiffKind;

  /** {@link DepthCounter} instance. */
  private final DepthCounter mDepth;

  /** Key of "root" node in new revision. */
  private long mRootKey;

  /** Root key of old revision. */
  private final long mOldRootKey;

  /**
   * Determines if {@link XdmNodeReadTrx} on newer revision moved to the node denoted by
   * {@code mNewStartKey}.
   */
  private final boolean mNewRtxMoved;

  /**
   * Determines if {@link XdmNodeReadTrx} on older revision moved to the node denoted by
   * {@code mOldStartKey}.
   */
  private final boolean mOldRtxMoved;

  /** Determines if the GUI uses the algorithm or not. */
  private final boolean mIsGUI;

  /** Determines if it's the first diff-comparison. */
  private boolean mIsFirst;

  /** {@link XdmNodeReadTrx} on new revision. */
  private final XdmNodeReadTrx mNewRtx;

  /** {@link XdmNodeReadTrx} on old revision. */
  private final XdmNodeReadTrx mOldRtx;

  /**
   * Constructor.
   *
   * @param pBuilder {@link Builder} reference
   * @throws SirixException if setting up transactions failes
   */
  AbstractDiff(final Builder pBuilder) throws SirixException {
    mDiffKind = checkNotNull(pBuilder).mKind;
    synchronized (pBuilder.mSession) {
      mNewRtx = pBuilder.mSession.beginNodeReadTrx(pBuilder.mNewRev);
      mOldRtx = pBuilder.mSession.beginNodeReadTrx(pBuilder.mOldRev);
      mHashKind = pBuilder.mHashKind;
    }
    mNewRtxMoved = mNewRtx.moveTo(pBuilder.mNewStartKey).hasMoved();
    mOldRtxMoved = mOldRtx.moveTo(pBuilder.mOldStartKey).hasMoved();
    if (mNewRtx.getKind() == Kind.DOCUMENT) {
      mNewRtx.moveToFirstChild();
    }
    if (mOldRtx.getKind() == Kind.DOCUMENT) {
      mOldRtx.moveToFirstChild();
    }
    mRootKey = pBuilder.mNewStartKey;
    mOldRootKey = pBuilder.mOldStartKey;

    synchronized (pBuilder.mObservers) {
      for (final DiffObserver observer : pBuilder.mObservers) {
        addObserver(observer);
      }
    }
    mDiff = DiffType.SAME;
    mDiffKind = pBuilder.mKind;
    mDepth = new DepthCounter(pBuilder.mNewDepth, pBuilder.mOldDepth);
    mIsGUI = pBuilder.mIsGUI;
    mIsFirst = true;
  }

  /**
   * Do the diff.
   *
   * @throws SirixException if setting up transactions failes
   */
  void diffMovement() throws SirixException {
    assert mHashKind != null;
    assert mNewRtx != null;
    assert mOldRtx != null;
    assert mDiff != null;
    assert mDiffKind != null;

    if (!mNewRtxMoved) {
      fireDeletes();
      if (!mIsGUI || mDepth.getNewDepth() == 0) {
        fireInserts();
      }
      done();
      return;
    }
    if (!mOldRtxMoved) {
      fireInserts();
      if (!mIsGUI || mDepth.getOldDepth() == 0) {
        fireDeletes();
      }
      done();
      return;
    }

    // Check first node.
    if (mHashKind == HashKind.NONE || mDiffKind == DiffOptimized.NO) {
      mDiff = diff(mNewRtx, mOldRtx, mDepth);
    } else {
      mDiff = optimizedDiff(mNewRtx, mOldRtx, mDepth);
    }
    mIsFirst = false;

    // Iterate over new revision (order of operators significant -- regarding
    // the OR).
    if (mDiff != DiffType.SAMEHASH) {
      while ((mOldRtx.getKind() != Kind.DOCUMENT && mDiff == DiffType.DELETED)
          || moveCursor(mNewRtx, Revision.NEW, Move.FOLLOWING)) {
        if (mDiff != DiffType.INSERTED) {
          moveCursor(mOldRtx, Revision.OLD, Move.FOLLOWING);
        }

        if (mNewRtx.getKind() != Kind.DOCUMENT || mOldRtx.getKind() != Kind.DOCUMENT) {
          if (mHashKind == HashKind.NONE || mDiffKind == DiffOptimized.NO) {
            mDiff = diff(mNewRtx, mOldRtx, mDepth);
          } else {
            mDiff = optimizedDiff(mNewRtx, mOldRtx, mDepth);
          }
        }
      }

      // Nodes deleted in old rev at the end of the tree.
      if (mOldRtx.getKind() != Kind.DOCUMENT) {
        mRootKey = mOldRootKey;
        // First time it might be EDiff.INSERTED where the cursor doesn't move.
        if (mDiff == DiffType.INSERTED) {
          mDiff = DiffType.DELETED;
          final DiffDepth depth = new DiffDepth(mDepth.getNewDepth(), mDepth.getOldDepth());
          fireDiff(mDiff, mNewRtx.getNodeKey(), mOldRtx.getNodeKey(), depth);
          emitNonStructuralDiff(mNewRtx, mOldRtx, depth, mDiff);
        }
        boolean moved = true;
        if (mDiffKind == DiffOptimized.HASHED && mDiff == DiffType.SAMEHASH) {
          moved = moveToFollowingNode(mOldRtx, Revision.OLD);
          if (moved) {
            mDiff = DiffType.DELETED;
            final DiffDepth depth = new DiffDepth(mDepth.getNewDepth(), mDepth.getOldDepth());
            fireDiff(mDiff, mNewRtx.getNodeKey(), mOldRtx.getNodeKey(), depth);
            emitNonStructuralDiff(mNewRtx, mOldRtx, depth, mDiff);
          }
        }
        if (moved) {
          while (moveCursor(mOldRtx, Revision.OLD, Move.DOCUMENT_ORDER)) {
            mDiff = DiffType.DELETED;
            final DiffDepth depth = new DiffDepth(mDepth.getNewDepth(), mDepth.getOldDepth());
            fireDiff(mDiff, mNewRtx.getNodeKey(), mOldRtx.getNodeKey(), depth);
            emitNonStructuralDiff(mNewRtx, mOldRtx, depth, mDiff);
          }
        }
      }
    }

    diffDone();
  }

  /**
   * Done processing diffs. Fire remaining diffs and signal that the algorithm is done.
   *
   * @throws SirixException if sirix fails to close the transactions
   */
  private void diffDone() throws SirixException {
    mNewRtx.close();
    mOldRtx.close();
    done();
  }

  /**
   * Fire {@code EDiff.DELETEs} for the whole subtree.
   */
  private void fireDeletes() {
    fireDiff(
        DiffType.DELETED, mNewRtx.getNodeKey(), mOldRtx.getNodeKey(),
        new DiffDepth(mDepth.getNewDepth(), mDepth.getOldDepth()));
    mIsFirst = false;
    while (moveCursor(mOldRtx, Revision.OLD, Move.DOCUMENT_ORDER)) {
      final DiffDepth depth = new DiffDepth(mDepth.getNewDepth(), mDepth.getOldDepth());
      fireDiff(DiffType.DELETED, mNewRtx.getNodeKey(), mOldRtx.getNodeKey(), depth);
      emitNonStructuralDiff(mNewRtx, mOldRtx, depth, DiffType.DELETED);
    }
  }

  /**
   * Fire {@code EDiff.INSERTs} for the whole subtree.
   */
  private void fireInserts() {
    fireDiff(
        DiffType.INSERTED, mNewRtx.getNodeKey(), mOldRtx.getNodeKey(),
        new DiffDepth(mDepth.getNewDepth(), mDepth.getOldDepth()));
    mIsFirst = false;
    while (moveCursor(mNewRtx, Revision.NEW, Move.DOCUMENT_ORDER)) {
      final DiffDepth depth = new DiffDepth(mDepth.getNewDepth(), mDepth.getOldDepth());
      fireDiff(DiffType.INSERTED, mNewRtx.getNodeKey(), mOldRtx.getNodeKey(), depth);
      emitNonStructuralDiff(mNewRtx, mOldRtx, depth, DiffType.DELETED);
    }
  }

  /**
   * Move cursor one node forward in pre order.
   *
   * @param rtx the {@link XdmNodeReadTrx} to use
   * @param revision the {@link Revision} constant
   * @return {@code true}, if cursor moved, {@code false} otherwise, if no nodes follow in document
   *         order
   */
  private boolean moveCursor(final XdmNodeReadTrx rtx, final Revision revision, final Move move) {
    assert rtx != null;
    assert revision != null;

    boolean moved = false;

    if (rtx.getKind() != Kind.DOCUMENT) {
      switch (mDiff) {
        case SAME:
        case SAMEHASH:
        case UPDATED:
          moved = moveToNext(rtx, revision);
          break;
        case REPLACED:
          moved = moveToFollowingNode(rtx, revision);
          break;
        case INSERTED:
        case DELETED:
          if (move == Move.FOLLOWING && (mDiff == DiffType.INSERTED || mDiff == DiffType.DELETED)) {
            if (rtx.getKind() == Kind.DOCUMENT) {
              moved = false;
            } else {
              moved = true;
            }
          } else {
            moved = moveToNext(rtx, revision);
          }
          break;
        case MOVEDFROM:
        case MOVEDTO:
        case REPLACEDNEW:
        case REPLACEDOLD:
        default:
      }
    }

    return moved;
  }

  private boolean moveToNext(final XdmNodeReadTrx rtx, final Revision revision) {
    boolean moved = false;
    if (rtx.hasFirstChild()) {
      if (rtx.getKind() != Kind.DOCUMENT && mDiffKind == DiffOptimized.HASHED
          && mDiff == DiffType.SAMEHASH) {
        moved = rtx.moveToRightSibling().hasMoved();

        if (!moved) {
          moved = moveToFollowingNode(rtx, revision);
        }
      } else {
        moved = rtx.moveToFirstChild().hasMoved();

        if (moved) {
          switch (revision) {
            case NEW:
              mDepth.incrementNewDepth();
              break;
            case OLD:
              mDepth.incrementOldDepth();
              break;
            default:
              // Must not happen.
          }
        }
      }
    } else if (rtx.hasRightSibling()) {
      if (rtx.getNodeKey() == mRootKey) {
        rtx.moveToDocumentRoot();
      } else {
        moved = rtx.moveToRightSibling().hasMoved();
      }
    } else {
      moved = moveToFollowingNode(rtx, revision);
    }
    return moved;
  }

  /**
   * Move to next following node.
   *
   * @param rtx the {@link XdmNodeReadTrx} to use
   * @param revision the {@link Revision} constant
   * @return true, if cursor moved, false otherwise
   */
  private boolean moveToFollowingNode(final XdmNodeReadTrx rtx, final Revision revision) {
    boolean moved = false;
    while (!rtx.hasRightSibling() && rtx.hasParent() && rtx.getNodeKey() != mRootKey) {
      moved = rtx.moveToParent().hasMoved();
      if (moved) {
        switch (revision) {
          case NEW:
            mDepth.decrementNewDepth();
            break;
          case OLD:
            mDepth.decrementOldDepth();
            break;
          default:
            // Must not happen.
        }
      }
    }

    if (rtx.getNodeKey() == mRootKey) {
      rtx.moveToDocumentRoot();
    }

    moved = rtx.moveToRightSibling().hasMoved();
    return moved;
  }

  /**
   * Diff of nodes.
   *
   * @param newRtx {@link XdmNodeReadTrx} on new revision
   * @param oldRtx {@link XdmNodeReadTrx} on old revision
   * @param depth {@link DepthCounter} container for current depths of both transaction cursors
   * @param paramFireDiff determines if a diff should be fired
   * @return kind of difference
   */
  DiffType diff(final XdmNodeReadTrx newRtx, final XdmNodeReadTrx oldRtx,
      final DepthCounter depth) {
    assert newRtx != null;
    assert oldRtx != null;
    assert depth != null;

    DiffType diff = DiffType.SAME;

    // Check for modifications.
    switch (newRtx.getKind()) {
      case DOCUMENT:
      case TEXT:
      case ELEMENT:
        if (checkNodes(newRtx, oldRtx)) {
          final DiffDepth diffDepth = new DiffDepth(depth.getNewDepth(), depth.getOldDepth());
          fireDiff(diff, newRtx.getNodeKey(), oldRtx.getNodeKey(), diffDepth);
          emitNonStructuralDiff(newRtx, oldRtx, diffDepth, diff);
        } else {
          diff = diffAlgorithm(newRtx, oldRtx, depth);
        }
        break;
      // $CASES-OMITTED$
      default:
        // Do nothing.
    }

    return diff;
  }

  /**
   * Optimized diff, which skips unnecessary comparsions.
   *
   * @param newRtx {@link XdmNodeReadTrx} on new revision
   * @param oldRtx {@link XdmNodeReadTrx} on old revision
   * @param depth {@link DepthCounter} container for current depths of both transaction cursors
   * @param paramFireDiff determines if a diff should be fired
   * @return kind of difference
   */
  DiffType optimizedDiff(final XdmNodeReadTrx newRtx, final XdmNodeReadTrx oldRtx,
      final DepthCounter depth) {
    assert newRtx != null;
    assert oldRtx != null;
    assert depth != null;

    DiffType diff = DiffType.SAMEHASH;

    // Check for modifications.
    switch (newRtx.getKind()) {
      case DOCUMENT:
      case TEXT:
      case ELEMENT:
        if (newRtx.getNodeKey() != oldRtx.getNodeKey() || newRtx.getHash() != oldRtx.getHash()) {
          // Check if nodes are the same (even if subtrees may vary).
          if (checkNodes(newRtx, oldRtx)) {
            diff = DiffType.SAME;
            final DiffDepth diffDepth = new DiffDepth(depth.getNewDepth(), depth.getOldDepth());
            fireDiff(diff, newRtx.getNodeKey(), oldRtx.getNodeKey(), diffDepth);
            emitNonStructuralDiff(newRtx, oldRtx, diffDepth, diff);
          } else {
            diff = diffAlgorithm(newRtx, oldRtx, depth);
          }
        } else {
          final DiffDepth diffDepth = new DiffDepth(depth.getNewDepth(), depth.getOldDepth());
          fireDiff(diff, newRtx.getNodeKey(), oldRtx.getNodeKey(), diffDepth);
          emitNonStructuralDiff(newRtx, oldRtx, diffDepth, diff);
        }
        break;
      // $CASES-OMITTED$
      default:
        // Do nothing.
    }

    return diff;
  }

  /**
   * Main algorithm to compute diffs between two nodes.
   *
   * @param newRtx {@link XdmNodeReadTrx} on new revision
   * @param oldRtx {@link XdmNodeReadTrx} on old revision
   * @param depth {@link DepthCounter} container for current depths of both transaction cursors
   * @return kind of diff
   */
  private DiffType diffAlgorithm(final XdmNodeReadTrx newRtx, final XdmNodeReadTrx oldRtx,
      final DepthCounter depth) {
    assert newRtx != null;
    assert oldRtx != null;
    assert depth != null;
    DiffType diff = null;

    if (depth.getOldDepth() > depth.getNewDepth()) { // Check if node has been
                                                     // deleted.
      diff = DiffType.DELETED;
      emitDiffs(diff);
    } else if (checkUpdate(newRtx, oldRtx)) { // Check if node has been updated.
      diff = DiffType.UPDATED;
      final DiffDepth diffDepth = new DiffDepth(depth.getNewDepth(), depth.getOldDepth());
      fireDiff(diff, newRtx.getNodeKey(), oldRtx.getNodeKey(), diffDepth);
      emitNonStructuralDiff(newRtx, oldRtx, diffDepth, diff);
    } else if (checkReplace(newRtx, oldRtx)) { // Check if node has been
                                               // replaced.
      diff = DiffType.REPLACED;
    } else {
      final long oldKey = oldRtx.getNodeKey();
      final boolean movedOld = oldRtx.moveTo(newRtx.getNodeKey()).hasMoved();
      oldRtx.moveTo(oldKey);

      final long newKey = newRtx.getNodeKey();
      final boolean movedNew = newRtx.moveTo(oldRtx.getNodeKey()).hasMoved();
      newRtx.moveTo(newKey);

      if (!movedOld) {
        diff = DiffType.INSERTED;
      } else if (!movedNew) {
        diff = DiffType.DELETED;
      } else {
        // Determine if one of the right sibling matches.
        EFoundEqualNode found = EFoundEqualNode.FALSE;

        while (oldRtx.hasRightSibling() && oldRtx.moveToRightSibling().hasMoved()
            && found == EFoundEqualNode.FALSE) {
          if (checkNodes(newRtx, oldRtx)) {
            found = EFoundEqualNode.TRUE;
            break;
          }
        }

        oldRtx.moveTo(oldKey);
        diff = found.kindOfDiff();
      }

      mDiff = diff;
      emitDiffs(diff);
    }

    assert diff != null;
    return diff;
  }

  /**
   * Emit diffs for {@code INSERTED} or {@code DELETED} nodes and traverse accordingly.
   *
   * @param diff kind of diff
   */
  private void emitDiffs(final DiffType diff) {
    final Revision revision = diff == DiffType.DELETED
        ? Revision.OLD
        : Revision.NEW;
    final int depth = diff == DiffType.DELETED
        ? mDepth.getOldDepth()
        : mDepth.getNewDepth();
    final XdmNodeReadTrx rtx = diff == DiffType.DELETED
        ? mOldRtx
        : mNewRtx;
    do {
      final DiffDepth diffDepth = new DiffDepth(mDepth.getNewDepth(), mDepth.getOldDepth());
      fireDiff(diff, mNewRtx.getNodeKey(), mOldRtx.getNodeKey(), diffDepth);
      emitNonStructuralDiff(mNewRtx, mOldRtx, diffDepth, diff);
    } while (moveCursor(rtx, revision, Move.DOCUMENT_ORDER)
        && ((diff == DiffType.INSERTED && mDepth.getNewDepth() > depth)
            || (diff == DiffType.DELETED && mDepth.getOldDepth() > depth)));
  }

  /**
   * Check {@link QName} of nodes.
   *
   * @param newRtx {@link XdmNodeReadTrx} on new revision
   * @param oldRtx {@link XdmNodeReadTrx} on old revision
   * @return {@code true} if nodes are "equal" according to their {@link QName} s, {@code false}
   *         otherwise
   */
  static boolean checkName(final XdmNodeReadTrx newRtx, final XdmNodeReadTrx oldRtx) {
    boolean found = false;
    if (newRtx.getKind() == oldRtx.getKind()) {
      switch (newRtx.getKind()) {
        case ELEMENT:
          if (newRtx.getPrefixKey() == oldRtx.getPrefixKey()
              && newRtx.getLocalNameKey() == oldRtx.getLocalNameKey()) {
            found = true;
          }
          break;
        case TEXT:
          if (newRtx.getValue().equals(oldRtx.getValue())) {
            found = true;
          }
          break;
        // $CASES-OMITTED$
        default:
      }
    }
    return found;
  }

  /**
   * Check if nodes are equal excluding subtrees.
   *
   * @param newRtx {@link XdmNodeReadTrx} on new revision
   * @param oldRtx {@link XdmNodeReadTrx} on old revision
   * @return true if nodes are "equal", otherwise false
   */
  abstract boolean checkNodes(final XdmNodeReadTrx newRtx, final XdmNodeReadTrx oldRtx);

  abstract void emitNonStructuralDiff(final XdmNodeReadTrx newRtx, final XdmNodeReadTrx oldRtx,
      final DiffDepth depth, final DiffType diff);

  /**
   * Check for a replace of a node.
   *
   * @param newRtx first {@link XdmNodeReadTrx} instance
   * @param oldRtx second {@link XdmNodeReadTrx} instance
   * @return true if node has been replaced, false otherwise
   */
  boolean checkReplace(final XdmNodeReadTrx newRtx, final XdmNodeReadTrx oldRtx) {
    boolean replaced = false;
    if (newRtx.getNodeKey() != oldRtx.getNodeKey()) {
      final long newKey = newRtx.getNodeKey();
      boolean movedNewRtx = newRtx.moveToRightSibling().hasMoved();
      final long oldKey = oldRtx.getNodeKey();
      boolean movedOldRtx = oldRtx.moveToRightSibling().hasMoved();
      if (movedNewRtx && movedOldRtx) {
        if (newRtx.getNodeKey() == oldRtx.getNodeKey()) {
          replaced = true;
        } else {
          while (newRtx.hasRightSibling() && oldRtx.hasRightSibling()) {
            newRtx.moveToRightSibling();
            oldRtx.moveToRightSibling();
            if (newRtx.getNodeKey() == oldRtx.getNodeKey()) {
              replaced = true;
              break;
            }
          }
        }
      } else if (!movedNewRtx && !movedOldRtx
          && (mDiff == DiffType.SAME || mDiff == DiffType.SAMEHASH)) {
        movedNewRtx = newRtx.moveToParent().hasMoved();
        movedOldRtx = oldRtx.moveToParent().hasMoved();

        if (movedNewRtx && movedOldRtx && newRtx.getNodeKey() == oldRtx.getNodeKey()) {
          replaced = true;
        }
      }
      newRtx.moveTo(newKey);
      oldRtx.moveTo(oldKey);

      if (replaced) {
        final long newNodeKey = newRtx.getNodeKey();
        final long oldNodeKey = oldRtx.getNodeKey();
        final Axis oldAxis = new DescendantAxis(oldRtx, IncludeSelf.YES);
        final Axis newAxis = new DescendantAxis(newRtx, IncludeSelf.YES);
        while (oldAxis.hasNext()) {
          oldAxis.next();
          fireDiff(
              DiffType.REPLACEDOLD, newRtx.getNodeKey(), oldRtx.getNodeKey(),
              new DiffDepth(mDepth.getNewDepth(), mDepth.getOldDepth()));
          adjustDepth(oldRtx, oldNodeKey, Revision.OLD);
        }

        while (newAxis.hasNext()) {
          newAxis.next();
          fireDiff(
              DiffType.REPLACEDNEW, newRtx.getNodeKey(), oldRtx.getNodeKey(),
              new DiffDepth(mDepth.getNewDepth(), mDepth.getOldDepth()));
          adjustDepth(newRtx, newNodeKey, Revision.NEW);
        }
        newRtx.moveTo(newNodeKey);
        oldRtx.moveTo(oldNodeKey);
        mDiff = DiffType.REPLACED;
        // final IAxis oldAxis = new DescendantAxis(pOldRtx, EIncludeSelf.YES);
        // final IAxis newAxis = new DescendantAxis(pNewRtx, EIncludeSelf.YES);
        // if (pNewRtx.getDescendantCount() >= pOldRtx
        // .getDescendantCount()) {
        // while (newAxis.hasNext()) {
        // newAxis.next();
        // boolean moved = false;
        // if (oldAxis.hasNext()) {
        // oldAxis.next();
        // moved = true;
        // }
        // fireDiff(EDiff.REPLACEDNEW, pNewRtx, pOldRtx,
        // new DiffDepth(mDepth.getNewDepth(), mDepth.getOldDepth()));
        // adjustDepth(newAxis.getTransaction(), newNodeKey, ERevision.NEW);
        // if (moved) {
        // adjustDepth(oldAxis.getTransaction(), oldNodeKey, ERevision.OLD);
        // }
        // }
        //
        // } else {
        // while (oldAxis.hasNext()) {
        // oldAxis.next();
        // boolean moved = false;
        // if (newAxis.hasNext()) {
        // newAxis.next();
        // moved = true;
        // }
        // fireDiff(EDiff.REPLACEDOLD, pNewRtx, pOldRtx,
        // new DiffDepth(mDepth.getNewDepth(), mDepth.getOldDepth()));
        // adjustDepth(oldAxis.getTransaction(), oldNodeKey, ERevision.OLD);
        // if (moved) {
        // adjustDepth(newAxis.getTransaction(), newNodeKey, ERevision.NEW);
        // }
        // }
        // }
        newRtx.moveTo(newNodeKey);
        oldRtx.moveTo(oldNodeKey);
      }
    }
    return replaced;
  }

  /**
   * Adjust the depth.
   *
   * @param rtx the transaction to simulate moves
   * @param startNodeKey the start node key
   * @param revision revision to iterate over
   */
  private void adjustDepth(final XdmNodeReadTrx rtx, final @Nonnegative long startNodeKey,
      final Revision revision) {
    assert rtx != null;
    assert startNodeKey >= 0;
    assert revision != null;
    final long nodeKey = rtx.getNodeKey();
    if (rtx.hasFirstChild()) {
      switch (revision) {
        case NEW:
          mDepth.incrementNewDepth();
          break;
        case OLD:
          mDepth.incrementOldDepth();
          break;
        default:
          // Must not happen.
      }
    } else {
      while (!rtx.hasRightSibling() && rtx.hasParent() && rtx.getNodeKey() != startNodeKey) {
        rtx.moveToParent();
        switch (revision) {
          case NEW:
            mDepth.decrementNewDepth();
            break;
          case OLD:
            mDepth.decrementOldDepth();
            break;
          default:
            // Must not happen.
        }
      }
    }
    rtx.moveTo(nodeKey);
  }

  /**
   * Check for an update of a node.
   *
   * @param newRtx first {@link XdmNodeReadTrx} instance
   * @param oldRtx second {@link XdmNodeReadTrx} instance
   * @return kind of diff
   */
  boolean checkUpdate(final XdmNodeReadTrx newRtx, final XdmNodeReadTrx oldRtx) {
    if (mIsFirst) {
      return newRtx.getNodeKey() == oldRtx.getNodeKey();
    }
    return newRtx.getNodeKey() == oldRtx.getNodeKey()
        && newRtx.getParentKey() == oldRtx.getParentKey()
        && mDepth.getNewDepth() == mDepth.getOldDepth();
  }
}
