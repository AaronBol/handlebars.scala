package com.gilt.handlebars

import com.gilt.handlebars._
import org.slf4j.{Logger, LoggerFactory}

trait Context[T] {
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  val context: T

  def invoke[A](methodName: String, args: List[A] = Nil): Option[Any] = {
    getMethod(methodName, args).flatMap(invoke(_, args))
  }

  def invoke[A](method: java.lang.reflect.Method, args: List[A]): Option[Any] = {
    logger.debug("Invoking method: '%s' with arguments: [%s].".format(method.getName, args.mkString(",")))
    try {
      Some(method.invoke(context, args.map(_.asInstanceOf[AnyRef]): _*))
    } catch {
      case e: java.lang.IllegalArgumentException => None
    }
  }

  def getMethod[A](name: String, args: List[A] = Nil) = {
    context.getClass.getMethods find { method =>
      method.getName == name && method.getParameterTypes.length == args.length
    }
  }

}

case class RootContext[T](context: T) extends Context[T]

case class ChildContext[T, P](context: T, parent: Context[P]) extends Context[T]

object HandlebarsVisitor {
  def apply[T](base: T) = new HandlebarsVisitor(new RootContext(base))
}

class HandlebarsVisitor[T](context: Context[T]) {
  private val logger: Logger = LoggerFactory.getLogger(getClass)
  logger.debug("Created a visitor with context: %s".format(context.context))

  def visit(node: Node): String = node match {
    case Content(content) => content
    case Identifier(ident) => context.invoke(ident).getOrElse("").toString
    case Path(path) => resolvePath(path).getOrElse(new RootContext("")).context.toString
    case Comment(_) => ""
    case Partial(partial) => compilePartial(partial).getOrElse("")
    case Mustache(stache, params, escaped) => resolveMustache(stache, params, escape = escaped)
    case Section(stache, value, inverted) => renderSection(stache.value, stache.parameters, value, inverted).getOrElse("")
    case Program(children) => children.map(visit).mkString
    case _ => toString
  }

  def resolvePath(list: List[Identifier], args: List[Context[Any]] = Nil): Option[Context[Any]] = {
    val resolution = list.foldLeft(None: Option[Context[Any]]) { (someContext, identifier) =>
      someContext.orElse(Some(context)).flatMap { aContext =>
        if (identifier.value == "..") {
          aContext match {
            case ChildContext(_, parent) => Some(parent)
            case _ => None
          }
        } else {
          aContext.invoke(identifier.value, args.map(_.context)).map(new ChildContext(_, aContext))
        }
      }
    }
    resolution orElse {
      logger.debug("Could not find identifier: '%s' in context. Searching in helpers.".format(list.map(_.value).mkString(".")))
      helpers.get(list.head.value) map { fn =>
        logger.debug("Found: '%s' in helpers.".format(list.head.value))
        new ChildContext(fn(args.map(_.context), this, Some(context.context)), context)
      }
    }
  }

  def resolveMustache(path: Path, parameters: List[Path], escape: Boolean = true): String = {
    val args = getArguments(parameters)
    val lookup = resolvePath(path.value, args)
    val resolution = lookup.getOrElse(new RootContext("")).context.toString
    if (escape)
      scala.xml.Utility.escape(resolution)
    else
      resolution
  }

  def renderSection(path: Path, parameters: List[Path], program: Program, inverted: Boolean = false): Option[String] = {
    resolvePath(path.value, getArguments(parameters)).map { block =>
      val visitor = fn(block.context)(program)
      logger.debug("Evaluated block as: %s".format(visitor))
      visitor
    } orElse { 
      if (inverted) {
        logger.debug("Inverting the block for: %s".format(path))
        Some(visit(program)) 
      } else {
        None 
      }
    }
  }

  def getArguments(paths: List[Path]) = paths flatMap {path => resolvePath(path.value)}

  def compilePartial(path: Path): Option[String] = {
    resolvePath(path.value).map { context =>
      this visit HandlebarsGrammar().scan(context.context.toString)
    }
  }

  // Helpers are a Map[String, (Context[Any], Visitor??) => Any])
  def helpers: Map[String, (Any, HandlebarsVisitor[T], Option[T]) => Any] = Map(
    "with" -> ((context, options, parent) => options.fn(context)),
    "noop" -> ((context, options, parent) => options.fn(parent))
  )

  // Mimicking the options of Handlebarsjs
  def fn[A](value: A) = {
    logger.debug("Preparing Context for new value: %s".format(value))
    val block = new ChildContext(value, context)

    (program: Program) => value match {
      case list:Iterable[_] => list.map(i => new HandlebarsVisitor(new ChildContext(i, block)).visit(program)).mkString
      case fun:Function1[_,_] => fun.asInstanceOf[Function1[Program,String]].apply(program).toString
      case Some(v) => new HandlebarsVisitor(new ChildContext(v, block)).visit(program)
      case _ => new HandlebarsVisitor(block).visit(program)
    }
  }

}