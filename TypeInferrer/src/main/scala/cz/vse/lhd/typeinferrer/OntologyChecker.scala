package cz.vse.lhd.typeinferrer

/**
 * Created by propan on 9. 4. 2015.
 */
trait OntologyChecker {

  def isType(name: String): Boolean

  def isTransitiveSubtype(superType: String, subType: String): Boolean

}
