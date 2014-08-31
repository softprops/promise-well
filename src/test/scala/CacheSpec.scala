package promisewell

import org.scalatest.FunSpec
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import util.Success

class CacheSpec extends FunSpec {
  describe("Cache") {
    it ("should cap items") {
      val capped = Cache.capped[Int, Int](maxCapacity = 2)
      capped(1, () => Future(1))
      capped(2, () => Future(2))
      capped(3, () => Future(3))
      assert(capped.get(1) === None)
      val two = capped.get(2)
      val three = capped.get(3)
      assert(two.isDefined === true)
      two.map(_.onComplete {
        case result => assert(result == Success(2))
      })
      assert(three.isDefined === true)
      three.map(_.onComplete {
        case result => assert(result == Success(3))
      })
    }
  }
}
