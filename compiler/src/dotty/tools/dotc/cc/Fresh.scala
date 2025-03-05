package dotty.tools
package dotc
package cc

import core.*
import Types.*, Symbols.*, Contexts.*, Annotations.*, Flags.*
import StdNames.nme
import ast.tpd.*
import Decorators.*
import typer.ErrorReporting.errorType
import Names.TermName
import NameKinds.ExistentialBinderName
import NameOps.isImpureFunction
import reporting.Message
import util.SimpleIdentitySet.empty
import CaptureSet.{Refs, emptyRefs, NarrowingCapabilityMap}
import dotty.tools.dotc.util.SimpleIdentitySet

/** A module for handling Fresh types. Fresh instances are top types that keep
 *  track of what they hide when capabilities get widened by subsumption to fresh.
 *  The module implements operations to convert between regular caps.cap and
 *  Fresh instances. Fresh(...) is encoded as `caps.cap @freshCapability(...)` where
 *  `freshCapability(...)` is a special kind of annotation of type `Fresh.Annot`
 *  that contains a hidden set.
 */
object Fresh:

  /** The annotation of a Fresh instance */
  case class Annot(hidden: CaptureSet.HiddenSet, binder: MethodType | NoType.type) extends Annotation:
    override def symbol(using Context) = defn.FreshCapabilityAnnot
    override def tree(using Context) = New(symbol.typeRef, Nil)
    override def derivedAnnotation(tree: Tree)(using Context): Annotation = this

    private var myOriginalBinder = binder
    def originalBinder: MethodType = myOriginalBinder.asInstanceOf[MethodType]

    def derivedAnnotation(binder: MethodType | NoType.type)(using Context): Annotation =
      if this.binder eq binder then this
      else
        val ann = Annot(hidden, binder)
        ann.myOriginalBinder = myOriginalBinder
        ann

    override def hash: Int = hidden.hashCode
    override def eql(that: Annotation) = that match
      case Annot(hidden, binder) => (this.hidden eq hidden) && (this.binder eq binder)
      case _ => false

    override def mapWith(tm: TypeMap)(using Context) = tm match
      case tm: Substituters.SubstBindingMap[MethodType] @unchecked if tm.from eq binder =>
        derivedAnnotation(tm.to)
      case _ =>
        this
  end Annot

  /** Constructor and extractor methods for "fresh" capabilities */
  private def make(owner: Symbol)(using Context): CaptureRef =
    if ccConfig.useSepChecks then
      val hiddenSet = CaptureSet.HiddenSet(owner)
      val res = AnnotatedType(defn.captureRoot.termRef, Annot(hiddenSet, NoType))
      hiddenSet.owningCap = res
      //assert(hiddenSet.id != 3)
      res
    else
      defn.captureRoot.termRef

  def withOwner(owner: Symbol)(using Context): CaptureRef = make(owner)

  def apply()(using Context): CaptureRef = make(NoSymbol)

  def unapply(tp: AnnotatedType): Option[CaptureSet.HiddenSet] = tp.annot match
    case Annot(hidden, binder) if !binder.exists => Some(hidden)
    case _ => None

  /** Create an existential */
  def existential(binder: MethodType)(using Context): AnnotatedType =
    val hiddenSet = CaptureSet.HiddenSet(NoSymbol)
    val res = AnnotatedType(defn.captureRoot.termRef, Annot(hiddenSet, binder))
    hiddenSet.owningCap = res
    //assert(hiddenSet.id != 9, binder.show)
    res

  /** The initial elements (either 0 or 1) of a hidden set created for given `owner`.
   *  If owner `x` is a trackable this is `x*` if reach` is true, or `x` otherwise.
   */
  private def ownerToHidden(owner: Symbol, reach: Boolean)(using Context): Refs =
    val ref = owner.termRef
    if reach then
      if ref.isTrackableRef then SimpleIdentitySet(ref.reach) else emptyRefs
    else
      if ref.isTracked then SimpleIdentitySet(ref) else emptyRefs

  /** Map each occurrence of cap to a different Sep.Cap instance */
  class FromCap(owner: Symbol)(using Context) extends BiTypeMap, FollowAliasesMap:
    thisMap =>

    override def apply(t: Type) =
      if variance <= 0 then t
      else t match
        case t: CaptureRef if t.isCap =>
          Fresh.withOwner(owner)
        case t @ CapturingType(_, _) =>
          mapOver(t)
        case t @ AnnotatedType(parent, ann) =>
          val parent1 = this(parent)
          if ann.symbol.isRetains && ann.tree.toCaptureSet.containsCap then
            this(CapturingType(parent1, ann.tree.toCaptureSet))
          else
            t.derivedAnnotatedType(parent1, ann)
        case _ =>
          mapFollowingAliases(t)

    override def toString = "CapToFresh"

    lazy val inverse: BiTypeMap & FollowAliasesMap = new BiTypeMap with FollowAliasesMap:
      def apply(t: Type): Type = t match
        case t @ Fresh(_) => defn.captureRoot.termRef
        case t @ CapturingType(_, refs) => mapOver(t)
        case _ => mapFollowingAliases(t)

      def inverse = thisMap
      override def toString = thisMap.toString + ".inverse"

  end FromCap

  /** Maps cap to fresh */
  def fromCap(tp: Type, owner: Symbol = NoSymbol)(using Context): Type =
    if ccConfig.useSepChecks then FromCap(owner)(tp) else tp

  /** Maps fresh to cap */
  def toCap(tp: Type)(using Context): Type =
    if ccConfig.useSepChecks then FromCap(NoSymbol).inverse(tp) else tp

  /** If `refs` contains an occurrence of `cap` or `cap.rd`, the current context
   *  with an added property PrintFresh. This addition causes all occurrences of
   *  `Fresh` to be printed as `fresh` instead of `cap`, so that one avoids
   *  confusion in error messages.
   */
  def printContext(refs: (Type | CaptureSet)*)(using Context): Context =
    def hasCap = new TypeAccumulator[Boolean]:
      def apply(x: Boolean, t: Type) =
        x || t.dealiasKeepAnnots.match
          case Fresh(_) => false
          case t: TermRef => t.isCap || this(x, t.widen)
          case x: ThisType => false
          case _ => foldOver(x, t)
    def containsFresh(x: Type | CaptureSet): Boolean = x match
      case tp: Type =>
        hasCap(false, tp)
      case refs: CaptureSet =>
        refs.elems.exists(_.stripReadOnly.isCap)

    if refs.exists(containsFresh) then ctx.withProperty(PrintFresh, Some(()))
    else ctx
  end printContext
end Fresh





