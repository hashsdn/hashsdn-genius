/*
 * Copyright (c) 2016, 2017 Ericsson India Global Services Pvt Ltd. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.genius.interfacemanager.renderer.ovs.confighelpers;

import com.google.common.util.concurrent.ListenableFuture;
import java.util.ArrayList;
import java.util.List;
import org.opendaylight.controller.md.sal.binding.api.DataBroker;
import org.opendaylight.controller.md.sal.binding.api.WriteTransaction;
import org.opendaylight.genius.interfacemanager.commons.InterfaceManagerCommonUtils;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.interfaces.rev140508.interfaces.Interface;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.interfaces.rev140508.interfaces.InterfaceKey;
import org.opendaylight.yang.gen.v1.urn.opendaylight.genius.idmanager.rev160406.IdManagerService;
import org.opendaylight.yang.gen.v1.urn.opendaylight.genius.interfacemanager.rev160406.IfL2vlan;
import org.opendaylight.yang.gen.v1.urn.opendaylight.genius.interfacemanager.rev160406.ParentRefs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OvsVlanMemberConfigAddHelper {
    private static final Logger LOG = LoggerFactory.getLogger(OvsVlanMemberConfigAddHelper.class);

    private final DataBroker dataBroker;
    private final IdManagerService idManager;

    public OvsVlanMemberConfigAddHelper(DataBroker dataBroker, IdManagerService idManager) {
        this.dataBroker = dataBroker;
        this.idManager = idManager;
    }

    public DataBroker getDataBroker() {
        return dataBroker;
    }

    public IdManagerService getIdManager() {
        return idManager;
    }

    public List<ListenableFuture<Void>> addConfiguration(ParentRefs parentRefs, Interface interfaceNew) {
        LOG.info("adding vlan member configuration for interface {}", interfaceNew.getName());
        List<ListenableFuture<Void>> futures = new ArrayList<>();
        WriteTransaction writeTransaction = dataBroker.newWriteOnlyTransaction();
        InterfaceManagerCommonUtils.createInterfaceChildEntry(parentRefs.getParentInterface(), interfaceNew.getName(),
                writeTransaction);
        futures.add(writeTransaction.submit());

        InterfaceKey interfaceKey = new InterfaceKey(parentRefs.getParentInterface());
        Interface ifaceParent = InterfaceManagerCommonUtils.getInterfaceFromConfigDS(interfaceKey, dataBroker);
        if (ifaceParent == null) {
            LOG.info("Parent Interface: {} not found when adding child interface: {}", parentRefs.getParentInterface(),
                    interfaceNew.getName());
            return futures;
        }

        IfL2vlan parentIfL2Vlan = ifaceParent.getAugmentation(IfL2vlan.class);
        if (parentIfL2Vlan == null || parentIfL2Vlan.getL2vlanMode() != IfL2vlan.L2vlanMode.Trunk) {
            LOG.error("Parent Interface: {} not of trunk Type when adding trunk-member: {}", ifaceParent, interfaceNew);
            return futures;
        }

        org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang
            .ietf.interfaces.rev140508.interfaces.state.Interface ifState = InterfaceManagerCommonUtils
                .getInterfaceState(parentRefs.getParentInterface(), dataBroker);
        InterfaceManagerCommonUtils.addStateEntry(interfaceNew.getName(), dataBroker, idManager, futures, ifState);
        return futures;
    }
}
