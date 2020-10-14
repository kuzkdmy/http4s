/*
 * Copyright 2013-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.headers

import java.nio.charset.StandardCharsets

import cats.data.NonEmptyList
import cats.syntax.all._
import org.http4s.internal.bug
import org.http4s.laws.discipline.ArbitraryInstances
import org.http4s.{ParseResult, Uri}
import org.scalacheck.{Arbitrary, Gen}

private[http4s] trait ForwardedArbitraryInstances
    extends ArbitraryInstances
    with ForwardedAuxiliaryGenerators {
  import Forwarded._

  // TODO: copied from `ArbitraryInstances` since the original is private.
  //       Consider re-using it somehow (discuss it).
  private implicit class ParseResultSyntax[A](self: ParseResult[A]) {
    def yolo: A = self.valueOr(e => throw bug(e.toString))
  }

  implicit val http4sTestingArbitraryForForwardedNodeObfuscated: Arbitrary[Node.Obfuscated] =
    Arbitrary(
      obfuscatedStringGen.map(Node.Obfuscated.fromString(_).yolo) :|
        "Node.Obfuscated")

  implicit val http4sTestingArbitraryForForwardedNodeName: Arbitrary[Node.Name] =
    Arbitrary(
      Gen.oneOf(
        Arbitrary.arbitrary[Uri.Ipv4Address].map(Node.Name(_)),
        Arbitrary.arbitrary[Uri.Ipv6Address].map(Node.Name(_)),
        Arbitrary.arbitrary[Node.Obfuscated],
        Gen.const(Node.Name.Unknown)
      ) :| "Node.Name")

  implicit val http4sTestingArbitraryForForwardedNodePort: Arbitrary[Node.Port] =
    Arbitrary(
      Gen.oneOf(
        portNumGen.map(Node.Port.fromInt(_).yolo),
        Arbitrary.arbitrary[Node.Obfuscated]
      ) :| "Node.Port")

  implicit val http4sTestingArbitraryForForwardedNode: Arbitrary[Node] =
    Arbitrary({
      for {
        nodeName <- Arbitrary.arbitrary[Node.Name]
        nodePort <- Gen.option(Arbitrary.arbitrary[Node.Port])
      } yield Node(nodeName, nodePort)
    } :| "Node")

  implicit val http4sTestingArbitraryForForwardedHost: Arbitrary[Host] = {
    val uriHostGen =
      Arbitrary
        .arbitrary[Uri.Host]
        .map {
          // Currently `Gen[Uri.Host]` generates pct-encoded `Uri.RegName`,
          // while the latter is designed to keep not encoded strings (see `Rfc3986Parser#Host`).
          // TODO: consider fixing `Gen[Uri.Host]`. See also #1651.
          case Uri.RegName(n) => Uri.RegName(Uri.decode(n.value, StandardCharsets.ISO_8859_1))
          case other => other
        }

    Arbitrary({
      for {
        // Increase frequency of empty host reg-names since it's a border case.
        // TODO: consider implementing it in `Gen[Uri.Host]` directly.
        uriHost <- Gen.oneOf(uriHostGen, Gen.const(Uri.RegName("")))
        portNum <- Gen.option(portNumGen)
      } yield Host.from(uriHost, portNum).yolo
    } :| "Host")
  }

  implicit val http4sTestingArbitraryForForwardedElement: Arbitrary[Element] =
    Arbitrary(
      Gen
        .atLeastOne(
          Arbitrary.arbitrary[Node].map(Element.fromFor),
          Arbitrary.arbitrary[Node].map(Element.fromBy),
          Arbitrary.arbitrary[Host].map(Element.fromHost),
          Arbitrary.arbitrary[Proto].map(Element.fromProto)
        )
        .map(_.reduceLeft[Element] {
          case (elem @ Element(None, _, _, _), Element(Some(forItem), None, None, None)) =>
            elem.withFor(forItem)
          case (elem @ Element(_, None, _, _), Element(None, Some(byItem), None, None)) =>
            elem.withBy(byItem)
          case (elem @ Element(_, _, None, _), Element(None, None, Some(hostItem), None)) =>
            elem.withHost(hostItem)
          case (elem @ Element(_, _, _, None), Element(None, None, None, Some(protoItem))) =>
            elem.withProto(protoItem)
          case (elem1, elem2) => throw bug(s"illegal combination of elements: $elem1 and $elem2")
        }) :|
        "Element"
    )

  implicit val http4sTestingArbitraryForForwarded: Arbitrary[Forwarded] =
    Arbitrary(
      Gen
        .nonEmptyListOf(Arbitrary.arbitrary[Element])
        .map(elems => Forwarded(NonEmptyList(elems.head, elems.tail))) :|
        "Forwarded"
    )
}
