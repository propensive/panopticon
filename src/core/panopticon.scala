/*
    Panopticon, version [unreleased]. Copyright 2023 Jon Pretty, Propensive OÜ.

    The primary distribution site is: https://propensive.com/

    Licensed under the Apache License, Version 2.0 (the "License"); you may not use this
    file except in compliance with the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software distributed under the
    License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
    either express or implied. See the License for the specific language governing permissions
    and limitations under the License.
*/

package panopticon

import rudiments.*

import language.dynamics
import scala.quoted.*

object opaques:
  opaque type Lens[FromType, PathType <: Tuple, ToType] = Int
  opaque type InitLens[FromType] = Int

  object Lens:
    def apply[FromType]: InitLens[FromType] = 0
    def make[FromType, PathType <: Tuple, ToType](): Lens[FromType, PathType, ToType] = 0

    transparent inline def fuse
        [FromType, PathType <: Tuple, ResultType]
        (lenses: Lens[FromType, PathType, ResultType]*)
        : Lens[FromType, Tuple, Tuple] =
      ${PanopticonMacros.fuse[FromType, PathType, ResultType]('lenses)}

  extension [FromType](initLens: InitLens[FromType])
    def apply
      [PathType <: Tuple, ToType]
      (fn: Target[FromType, EmptyTuple] => Target[ToType, PathType])
      : Lens[FromType, PathType, ToType] =
    0
    

class Target[FromType, PathType <: Tuple]() extends Dynamic:
  transparent inline def selectDynamic(member: String): Any =
    ${PanopticonMacros.resolve[FromType, PathType]('member)}

export opaques.Lens

extension [FromType, PathType <: Tuple, ToType](lens: Lens[FromType, PathType, ToType])
  def ++
      [ToType2, PathType2 <: Tuple]
      (right: Lens[ToType, PathType2, ToType2])
      : Lens[FromType, Tuple.Concat[PathType, PathType2], ToType2] =
    Lens.make()
  
  inline def get(target: FromType): ToType =
    ${PanopticonMacros.get[FromType, PathType, ToType]('target)}
  
  inline def set(target: FromType, newValue: ToType): FromType =
    ${PanopticonMacros.set[FromType, PathType, ToType]('target, 'newValue)}

trait MemberType[TargetType, LabelType <: String & Singleton]:
  type ReturnType

object PanopticonMacros:

  def fuse
      [FromType: Type, PathType <: Tuple: Type, ResultType: Type]
      (lenses: Expr[Seq[Lens[FromType, PathType, ResultType]]])(using Quotes)
      : Expr[Lens[FromType, Tuple, Tuple]] =
    import quotes.reflect.*
    
    def buildPathType
        (lenses: Expr[Seq[Lens[FromType, PathType, ResultType]]],
            path: TypeRepr = TypeRepr.of[EmptyTuple], result: TypeRepr = TypeRepr.of[EmptyTuple])
        : (TypeRepr, TypeRepr) =
      lenses match
        case _ =>
          (path, result)
        case '{ ($head: Lens[FromType, nextPathType, nextResultType]) +: ($tail: tailType) } =>
          (path.asType, result.asType) match
            case ('[pathType], '[resultType]) =>
              buildPathType(tail, TypeRepr.of[nextPathType *: (pathType & Tuple)],
                  TypeRepr.of[nextResultType *: (resultType & Tuple)])
      
      buildPathType(lenses)

    '{???}

  private def
      getPath[TupleType: Type](path: List[String] = Nil)(using Quotes): List[String] =
    import quotes.reflect.*

    Type.of[TupleType] match
      case '[ head *: tail ] => TypeRepr.of[head] match
        case ConstantType(StringConstant(str)) =>
          getPath[tail](str :: path)
      case _ => path
  
  def get
      [FromType: Type, PathType <: Tuple: Type, ToType: Type](value: Expr[FromType])
      (using Quotes): Expr[ToType] =
    import quotes.reflect.*
    
    def select(path: List[String], term: Term): Term =
      path match
        case Nil          => term
        case next :: tail => select(tail, Select(term, term.tpe.typeSymbol.fieldMember(next)))
      
    select(getPath[PathType](), value.asTerm).asExprOf[ToType]

  def set
      [FromType: Type, PathType <: Tuple: Type, ToType: Type](value: Expr[FromType], newValue: Expr[ToType])
      (using Quotes): Expr[FromType] =
    import quotes.reflect.*

    val fromTypeRepr: TypeRepr = TypeRepr.of[FromType]

    def rewrite(path: List[String], term: Term): Term =
      path match
        case Nil          => term
        case next :: tail =>
          val newParams = term.tpe.typeSymbol.caseFields.map: field =>
            if field.name == next then
              if tail == Nil then newValue.asTerm else rewrite(tail, Select(term, field))
            else Select(term, field)
          
          term.tpe.classSymbol match
            case Some(classSymbol) =>
              Apply(Select(New(TypeIdent(classSymbol)), term.tpe.typeSymbol.primaryConstructor),
                  newParams)
            case None =>
              fail(s"the type ${fromTypeRepr.show} does not have a primary constructor")
        
    rewrite(getPath[PathType](), value.asTerm).asExprOf[FromType]
  
  def resolve
      [TargetType: Type, TupleType <: Tuple: Type](member: Expr[String])(using Quotes): Expr[Any] =
    import quotes.reflect.*
    
    val fieldName = member.valueOrAbort
    val fieldNameType = ConstantType(StringConstant(fieldName)).asType
    val targetType = TypeRepr.of[TargetType]

    targetType.typeSymbol.caseFields.find(_.name == fieldName) match
      case None =>
        fail(s"the field $fieldName is not a member of ${targetType.show}")
      case Some(sym) => sym.info.asType match
        case '[returnType] => fieldNameType match
          case '[fieldName] =>
            '{Target[returnType, fieldName *: TupleType]()}
