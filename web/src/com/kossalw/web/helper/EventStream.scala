package com.kossalw.web.helper

import com.raquo.laminar.api.L._
import scala.util.{Failure, Success, Try}

implicit class EventStreamExt[A](self: EventStream[A]) {
  def recoverToCollect(project: Try[A] => Option[A]): EventStream[A] = 
    self
      .recoverToTry
      .map(project)
      .filter(_.isDefined)
      .map(_.get)
}
