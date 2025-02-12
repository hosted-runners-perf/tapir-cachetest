package sttp.tapir.grpc.protobuf

import sttp.tapir.Schema.SName
import sttp.tapir.SchemaType.{SDate, SDateTime, SInteger, SNumber, SProduct, SProductField, SString}
import sttp.tapir.{Schema, _}
import sttp.tapir.grpc.protobuf.model._

class EndpointToProtobufMessage {
  def apply(es: List[AnyEndpoint]): List[ProtobufMessage] =
    es.flatMap(forEndpoint)

  private def distinctBy[T, U](elements: List[T])(selector: T => U): List[T] = elements
    .groupBy(selector)
    .flatMap {
      case (_, hd :: _) => Seq(hd)
      case _            => Seq.empty[T]
    }
    .toList

  private def forEndpoint(e: AnyEndpoint): List[ProtobufMessage] =
    distinctBy(forInput(e.input) ++ forOutput(e.output))(_.name)

  private def forInput(input: EndpointInput[_]): List[ProtobufMessage] = {
    input match {
      case EndpointInput.FixedMethod(_, _, _)     => List.empty
      case EndpointInput.FixedPath(_, _, _)       => List.empty
      case EndpointInput.PathCapture(_, codec, _) => ???
      case EndpointInput.PathsCapture(_, _)       => List.empty
      case EndpointInput.Query(_, _, codec, _)    => ???
      case EndpointInput.Cookie(_, codec, _)      => ???
      case EndpointInput.QueryParams(_, _)        => List.empty
      case _: EndpointInput.Auth[_, _]            => List.empty
      case _: EndpointInput.ExtractFromRequest[_] => List.empty
      case EndpointInput.MappedPair(wrapped, _)   => ???
      case EndpointInput.Pair(left, right, _, _)  => forInput(left) ++ forInput(right)
      case op: EndpointIO[_]                      => forIO(op)
    }
  }

  private def forOutput(output: EndpointOutput[_]): List[ProtobufMessage] = {
    output match {
      case EndpointOutput.OneOf(variants, _)            => ???
      case EndpointOutput.StatusCode(_, _, _)           => List.empty
      case EndpointOutput.FixedStatusCode(_, _, _)      => List.empty
      case EndpointOutput.MappedPair(wrapped, _)        => forOutput(wrapped)
      case EndpointOutput.Void()                        => List.empty
      case EndpointOutput.Pair(left, right, _, _)       => forOutput(left) ++ forOutput(right)
      case EndpointOutput.WebSocketBodyWrapper(wrapped) => ???
      case op: EndpointIO[_]                            => forIO(op)
    }
  }

  private def forIO(io: EndpointIO[_]): List[ProtobufMessage] =
    io match {
      case EndpointIO.Pair(left, right, _, _)                            => forIO(left) ++ forIO(right)
      case EndpointIO.Header(_, codec, _)                                => ???
      case EndpointIO.Headers(_, _)                                      => List.empty
      case EndpointIO.Body(_, codec, _)                                  => fromCodec(codec)
      case EndpointIO.OneOfBody(variants, _)                             => variants.flatMap(v => forIO(v.bodyAsAtom))
      case EndpointIO.StreamBodyWrapper(StreamBodyIO(_, codec, _, _, _)) => ???
      case EndpointIO.MappedPair(wrapped, _)                             => forIO(wrapped)
      case EndpointIO.FixedHeader(_, _, _)                               => List.empty
      case EndpointIO.Empty(_, _)                                        => List.empty
    }

  private def fromCodec(codec: Codec[_, _, _]): List[ProtobufMessage] = {
    val rootSchema = codec.schema

    val msgs = availableMessagesFromSchema(rootSchema)

    msgs.flatMap { case (name, msgSchema) =>
      msgSchema.schemaType match {
        case SProduct(fields) =>
          val protoFields = fields.map { field =>
            // TODO files support?
            fromProductField(msgs)(field)
          }
          List(ProtobufMessage(name.fullName.split('.').last, protoFields)) // FIXME
        case _ => ???
      }
    }.toList
  }

  private def availableMessagesFromSchema(schema: Schema[_]): Map[SName, Schema[_]] = schema.schemaType match {
    case SProduct(fields) =>
      schema.name.map(name => Map(name -> schema)).getOrElse(Map.empty) ++
        fields.foldLeft(Map.empty[SName, Schema[_]])((m, field) => m ++ availableMessagesFromSchema(field.schema))
    case SchemaType.SCoproduct(subtypes, discriminator) => ???
    case _                                              => Map.empty
  }

  private def defaultScalarMappings(field: SProductField[_]): ProtobufScalarType = field.schema.schemaType match {
    case SString()                                           => ProtobufScalarType.ProtobufString
    case SInteger() if field.schema.format.contains("int64") => ProtobufScalarType.ProtobufInt64
    case SInteger()                                          => ProtobufScalarType.ProtobufInt32
    case SNumber() if field.schema.format.contains("float")  => ProtobufScalarType.ProtobufFloat
    case SNumber()                                           => ProtobufScalarType.ProtobufDouble
    case SchemaType.SBoolean()                               => ProtobufScalarType.ProtobufBool
    case SProduct(Nil)                                       => ProtobufScalarType.ProtobufEmpty
    case SchemaType.SBinary()                                => ProtobufScalarType.ProtobufBytes
    case SDateTime()                                         => ProtobufScalarType.ProtobufInt64
    case SDate()                                             => ProtobufScalarType.ProtobufInt64
    case in =>
      println(s"Not supported input [$in]") // FIXME
      ???
  }

  private def fromProductField(availableMessages: Map[SName, Schema[_]])(field: SProductField[_]): ProtobufMessageField = {
    val maybeCustomType = field.schema.attribute(ProtobufAttributes.ScalarValueAttribute)
    val maybeMessageRef = field.schema.name match {
      case Some(name) if availableMessages.contains(name) => Some(ProtobufMessageRef(name))
      case _                                              => None
    }
    val `type` = maybeCustomType.orElse(maybeMessageRef).getOrElse(defaultScalarMappings(field))

    ProtobufMessageField(`type`, field.name.name, None)
  }

}
