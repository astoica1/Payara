/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 1997-2014 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
// Portions Copyright [2016-2022] [Payara Foundation and/or its affiliates]

package org.glassfish.web.ha.authenticator;

import com.sun.enterprise.container.common.spi.util.JavaEEIOUtils;

import com.sun.enterprise.security.web.PayaraSingleSignOnEntry;
import org.apache.catalina.Container;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.Session;
import org.apache.catalina.authenticator.SingleSignOn;
import org.glassfish.web.ha.LogFacade;

import java.io.*;
import java.security.Principal;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Shing Wai Chan
 */
public class HASingleSignOnEntry extends PayaraSingleSignOnEntry {
    private static final Logger logger = LogFacade.getLogger();

    protected long maxIdleTime;

    protected JavaEEIOUtils ioUtils;

    protected HASingleSignOnEntryMetadata metadata = null;

    // default constructor is required by backing store
    public HASingleSignOnEntry() {
        this(null, null, null, null, null, 0, 0, 0, null);
    }

    public HASingleSignOnEntry(HASingleSignOn sso, Container container, HASingleSignOnEntryMetadata m,
            JavaEEIOUtils ioUtils) {
        this(m.getId(), null, m.getAuthType(),
                m.getUserName(), m.getRealmName(),
                m.getLastAccessTime(), m.getMaxIdleTime(), m.getVersion(),
                ioUtils);

        // GLASSFISH-21148: constructor called with null - don't forget to update metadata!
        Principal principal = parsePrincipal(m);
        updateCredentials(principal, m.authType, m.getUserName(), null);
        this.metadata.principalBytes = m.getPrincipalBytes() == null ? null : m.getPrincipalBytes().clone();

        for (HASessionData data: m.getHASessionDataSet()) {
            StandardContext context = (StandardContext)container.findChild(data.getContextPath());
            Session session = null;
            try {
                session = context.getManager().findSession(data.getSessionId());
            } catch(IOException ex) {
                throw new IllegalStateException("Cannot find the session: " + data.getSessionId(), ex);
            }
            if (session != null) {
                addSession(sso, m.getId(), session);
            }
        }
        logger.log(Level.FINER, "Loaded HA SSO entry from metadata. Principal: {}", getPrincipal());
    }

    // TODO: javadoc: difference between principal.getName and userName?
    public HASingleSignOnEntry(String id, Principal principal, String authType,
            String username, String realmName,
            long lastAccessTime, long maxIdleTime, long version,
            JavaEEIOUtils ioUtils) {

        super(id, version, principal, authType, username, realmName);
        this.lastAccessTime = lastAccessTime;
        this.maxIdleTime = maxIdleTime;
        this.ioUtils = ioUtils;

        this.metadata = new HASingleSignOnEntryMetadata(
                id, version, convertToByteArray(principal), authType,
                username, realmName,
                lastAccessTime, maxIdleTime);
        logger.log(Level.FINER, "Created HA SSO entry. Principal: {}", getPrincipal());
    }

    public HASingleSignOnEntryMetadata getMetadata() {
        return metadata;
    }

    public long getMaxIdleTime() {
        return maxIdleTime;
    }

    @Override
    public synchronized void addSession(SingleSignOn sso, String ssoId, Session session) {
        super.addSession(sso, ssoId,  session);
        metadata.addHASessionData(
                new HASessionData(session.getId(), session.getManager().getContext().getPath()));
    }

    @Override
    public synchronized void removeSession(Session session) {
        super.removeSession(session);
        metadata.removeHASessionData(new HASessionData(session.getId(),
                session.getManager().getContext().getPath()));
    }

    @Override
    public void setLastAccessTime(long lastAccessTime) {
        super.setLastAccessTime(lastAccessTime);
        metadata.setLastAccessTime(lastAccessTime);
    }

    @Override
    public long incrementAndGetVersion() {
        long ver = super.incrementAndGetVersion();
        metadata.setVersion(ver);
        return ver;
    }

    /** convert a principal into byte array */
    private byte[] convertToByteArray(Principal obj) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ObjectOutputStream oos = ioUtils.createObjectOutputStream(baos, true)) {
            oos.writeObject(obj);
            oos.flush();
            return baos.toByteArray();
        } catch(Exception ex) {
            throw new IllegalStateException("Could not convert principal to byte array", ex);
        }
    }

    /** Parse a principal from metadata */
    private Principal parsePrincipal(HASingleSignOnEntryMetadata m) {
        try (ObjectInputStream ois = ioUtils.createObjectInputStream(
                new BufferedInputStream(new ByteArrayInputStream(m.getPrincipalBytes())), true,
                this.getClass().getClassLoader(), 0L)) {
            return (Principal) ois.readObject();
      } catch (Exception ex) {
          throw new IllegalStateException("Could not parse principal from HA-SSO Metadata", ex);
      }
    }
}
