/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2017, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package io.narayana.lra.client.internal.proxy;

import io.narayana.lra.client.participant.LRAParticipant;
import io.narayana.lra.client.participant.LRAParticipantDeserializer;
import io.narayana.lra.client.participant.JoinLRAException;
import io.narayana.lra.client.participant.LRAManagement;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.faces.bean.ApplicationScoped;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Link;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.narayana.lra.client.internal.proxy.ParticipantProxyResource.LRA_PROXY_PATH;

@ApplicationScoped
public class ProxyService implements LRAManagement {
    private static final String COORDINATOR_PATH_NAME = "lra-coordinator"; // LRAClient.COORDINATOR_PATH_NAME
    private static final String TIMELIMIT_PARAM_NAME = "TimeLimit";  // LRAClient.TIMELIMIT_PARAM_NAME

    static private List<ParticipantProxy> participants; // TODO figure out why ProxyService is constructed twice

    private Client lcClient;
    private WebTarget lcTarget;

    private UriBuilder uriBuilder;

    @PostConstruct
    void init() throws MalformedURLException {
        if (participants == null) // TODO figure out why ProxyService is constructed twice
            participants = new ArrayList<>();

        int httpPort = Integer.getInteger("swarm.http.port", 8081);
        String httpHost = System.getProperty("swarm.http.host", "localhost");

        // TODO if the proxy is restarted on a different endpoint it should notify the recovery coordinator

        uriBuilder = UriBuilder.fromPath(LRA_PROXY_PATH + "/{lra}/{pid}");
        uriBuilder.scheme("http")
                .host(httpHost)
                .port(httpPort);

        String lcHost = System.getProperty("lra.http.host", "localhost");
        int lcPort = Integer.getInteger("lra.http.port", 8080);

        UriBuilder urib = UriBuilder.fromPath(COORDINATOR_PATH_NAME).scheme("http").host(lcHost).port(lcPort);

        lcClient = ClientBuilder.newClient();
        lcTarget = lcClient.target(urib.build());
    }

    @PreDestroy
    void fini() {
        if (lcClient != null) {
            lcClient.close();
            lcClient = null;
        }
    }

    private ParticipantProxy getProxy(URL lraId, String participantId) {
        int i = participants.indexOf(new ParticipantProxy(lraId, participantId));

        return (i == -1 ? null : participants.get(i));
    }

    private ParticipantProxy recreateProxy(URL lraId, String participantId, String participantData) {
        // TODO deserialize participantData - means that deserializers need to be registered at startup
        return new ParticipantProxy(lraId, participantId);
    }

    void notifyParticipant(URL lraId, String participantId, String participantData, boolean compensate) {
        ParticipantProxy proxy = getProxy(lraId, participantId);

        if (proxy == null) {
            /*
             * must be in a recovery scenario so recreate the proxy from the registered data
             */
            proxy = recreateProxy(lraId, participantId, participantData);
        }

        LRAParticipant LRAParticipant = proxy.getLRAParticipant();

        if (LRAParticipant == null && participantData != null && participantData.length() > 0)
            LRAParticipant = deserializeParticipant(proxy.getDeserializer(), participantData).orElse(null);

        if (LRAParticipant != null) {
            if (compensate)
                LRAParticipant.compensateWork(lraId); // let any NotFoundException propagate back to the coordinator
            else
                LRAParticipant.completeWork(lraId); // let any NotFoundException propagate back to the coordinator
            participants.remove(proxy);
        } else {
            System.err.printf("TODO recovery: null LRAParticipant for callback %s%n", lraId.toExternalForm());
        }
    }

    void notifyForget(URL lraId, String participantId) {
        ParticipantProxy proxy = getProxy(lraId, participantId);

        if (proxy != null)
            proxy.getLRAParticipant().forget(lraId);
    }

    String getStatus(URL lraId, String participantId) {
        ParticipantProxy proxy = getProxy(lraId, participantId);

        if (proxy != null)
            return proxy.getLRAParticipant().status(lraId).name();

        return null;
    }

    @Override
    public String joinLRA(LRAParticipant LRAParticipant, LRAParticipantDeserializer deserializer, URL lraId, Long timelimit)
            throws JoinLRAException {
        ParticipantProxy proxy = new ParticipantProxy(lraId, UUID.randomUUID().toString(), LRAParticipant, deserializer);

        try {
            String pId = proxy.getParticipantId();
            String lra = URLEncoder.encode(lraId.toExternalForm(), "UTF-8");
            UriBuilder clone = uriBuilder.clone();

            URI participantUri = clone.build(lra, pId);
            Link link = Link.fromUri(participantUri)
                    .rel("participant").type("text/plain")
                    .build();

            Optional<String> participantData = serializeParticipant(LRAParticipant); // TODO serialize LRAParticipant

            participants.add(proxy);

            Response response = lcTarget.path(lra)
                    .queryParam(TIMELIMIT_PARAM_NAME, timelimit)
                    .request()
                    .header("Link", link.toString())
                    .put(Entity.entity(participantData.orElse(""), MediaType.TEXT_PLAIN));

            if (response.getStatus() == Response.Status.PRECONDITION_FAILED.getStatusCode()) {
                throw new JoinLRAException(lraId, response.getStatus(), "Too late to join with this LRA", null);
            } else if (response.getStatus() != Response.Status.OK.getStatusCode()) {
                throw new JoinLRAException(lraId, response.getStatus(), "Unable to join with this LRA", null);
            }

            return response.readEntity(String.class);
        } catch (Exception e) {
            throw new JoinLRAException(lraId, 0, "Exception whilst joining with this LRA", e);
        }
    }

    private static Optional<String> serializeParticipant(final Serializable object) {
        try (final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(object);

            return Optional.of(Base64.getEncoder().encodeToString(baos.toByteArray()));
        } catch (final IOException e) {
            System.err.printf("ProxyService: participant serialization problem: %s%n", e.getMessage());

            return Optional.empty();
        }
    }

    private static Optional<LRAParticipant> deserializeParticipant(final LRAParticipantDeserializer deserializer, final String objectAsString) {
        final byte[] data = Base64.getDecoder().decode(objectAsString);

        try (final ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data))) {
            return Optional.of((LRAParticipant) ois.readObject());
        } catch (final IOException | ClassNotFoundException e) {
            if (deserializer != null) {
                LRAParticipant LRAParticipant = deserializer.deserialize(objectAsString.getBytes());

                if (LRAParticipant != null)
                    return Optional.of(LRAParticipant);
            }

            System.err.printf("ProxyService: participant deserialization problem: %s%n", e.getMessage());

            return Optional.empty();
        }
    }
}