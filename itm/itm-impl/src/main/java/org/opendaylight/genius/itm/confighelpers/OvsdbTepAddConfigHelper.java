/*
 * Copyright (c) 2016 Ericsson India Global Services Pvt Ltd. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.genius.itm.confighelpers;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.opendaylight.controller.md.sal.binding.api.DataBroker;
import org.opendaylight.controller.md.sal.binding.api.WriteTransaction;
import org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType;
import org.opendaylight.genius.itm.globals.ITMConstants;
import org.opendaylight.genius.itm.impl.ItmUtils;
import org.opendaylight.genius.mdsalutil.MDSALUtil;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.IpAddress;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.IpPrefix;
import org.opendaylight.yang.gen.v1.urn.opendaylight.genius.itm.rev160406.TransportZones;
import org.opendaylight.yang.gen.v1.urn.opendaylight.genius.itm.rev160406.transport.zones.*;
import org.opendaylight.yang.gen.v1.urn.opendaylight.genius.itm.rev160406.transport.zones.tepsnothostedintransportzone.UnknownVteps;
import org.opendaylight.yang.gen.v1.urn.opendaylight.genius.itm.rev160406.transport.zones.tepsnothostedintransportzone.UnknownVtepsBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.genius.itm.rev160406.transport.zones.tepsnothostedintransportzone.UnknownVtepsKey;
import org.opendaylight.yang.gen.v1.urn.opendaylight.genius.itm.rev160406.transport.zones.transport.zone.Subnets;
import org.opendaylight.yang.gen.v1.urn.opendaylight.genius.itm.rev160406.transport.zones.transport.zone.SubnetsBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.genius.itm.rev160406.transport.zones.transport.zone.SubnetsKey;
import org.opendaylight.yang.gen.v1.urn.opendaylight.genius.itm.rev160406.transport.zones.transport.zone.subnets.Vteps;
import org.opendaylight.yang.gen.v1.urn.opendaylight.genius.itm.rev160406.transport.zones.transport.zone.subnets.VtepsBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.genius.itm.rev160406.transport.zones.transport.zone.subnets.VtepsKey;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OvsdbTepAddConfigHelper {

    private static final Logger LOG = LoggerFactory.getLogger(OvsdbTepAddConfigHelper.class);

    /**
     * Adds the TEP into ITM configuration Datastore in one of the following cases:
     * 1) default transport zone
     * 2) Configured transport zone
     * 3) Unhosted transport zone
     *
     * @param tepIp TEP-IP address in string
     * @param strDpnId DPN datapath ID in string
     * @param tzName transport zone name in string
     * @param dataBroker data broker handle to perform operations on config datastore
     * @param wrTx WriteTransaction object
     */
    public static void addTepReceivedFromOvsdb(String tepIp, String strDpnId, String tzName,
        DataBroker dataBroker, WriteTransaction wrTx) {
        BigInteger dpnId = BigInteger.valueOf(0);

        if (strDpnId != null && !strDpnId.isEmpty()) {
            dpnId = MDSALUtil.getDpnId(strDpnId);
        }

        // Get tep IP
        IpAddress tepIpAddress = new IpAddress(tepIp.toCharArray());
        TransportZone tZone = null;

        // Case: TZ name is not given with CSS TEP.
        if (tzName.isEmpty()) {
            tzName = ITMConstants.DEFAULT_TRANSPORT_ZONE;
            // add TEP into default-TZ
            tZone = ItmUtils.getTransportZoneFromConfigDS(tzName, dataBroker);
            if (tZone == null) {
                LOG.error("Error: default-transport-zone is not yet created.");
                return;
            }
            LOG.trace("Add TEP into default-transport-zone.");
        } else {
            // Case: Add TEP into corresponding TZ created from Northbound.
            tZone = ItmUtils.getTransportZoneFromConfigDS(tzName, dataBroker);
            if (tZone == null) {
                // Case: TZ is not configured from Northbound, then add TEP into "teps-not-hosted-in-transport-zone"
                LOG.trace("Adding TEP with unknown TZ into teps-not-hosted-in-transport-zone.");
                addUnknownTzTepIntoTepsNotHosted(tzName, tepIpAddress, dpnId, dataBroker, wrTx);
                return;
            } else {
                LOG.trace("Add TEP into transport-zone already configured by Northbound.");
            }
        }

        // Get subnet list of corresponding TZ created from Northbound.
        List<Subnets> subnetList = tZone.getSubnets();
        String portName = "";

        IpPrefix subnetMaskObj = ItmUtils.getDummySubnet();

        if (subnetList == null || subnetList.isEmpty()) {
            if (subnetList == null) {
                subnetList = new ArrayList<Subnets>();
            }
            List<Vteps> vtepList = new ArrayList<Vteps>();
            LOG.trace("Add TEP in transport-zone when no subnet-list.");
            addVtepInITMConfigDS(subnetList, subnetMaskObj, vtepList, tepIpAddress, tzName, dpnId,
                portName, wrTx);
        } else {
            List<Vteps> vtepList = null;

            // subnet list already exists case; check for dummy-subnet
            for (Subnets subnet : subnetList) {
                if (subnet.getKey().getPrefix().equals(subnetMaskObj)) {
                    LOG.trace("Subnet exists in the subnet list of transport-zone {}.", tzName);
                    // get vtep list of existing subnet
                    vtepList = subnet.getVteps();
                    break;
                }
            }

            if (vtepList == null || vtepList.isEmpty()) {
                //  case: vtep list does not exist or it has no elements
                if (vtepList == null) {
                    vtepList = new ArrayList<Vteps>();
                }
                LOG.trace("Add TEP in transport-zone when no vtep-list for specific subnet.");
                addVtepInITMConfigDS(subnetList, subnetMaskObj, vtepList, tepIpAddress, tzName,
                    dpnId, portName, wrTx);
            } else {
                //  case: vtep list has elements
                boolean vtepFound = false;
                Vteps oldVtep = null;

                for (Vteps vtep : vtepList) {
                    if (vtep.getDpnId().equals(dpnId)) {
                        vtepFound = true;
                        oldVtep = vtep;
                        // get portName of existing vtep
                        portName = vtep.getPortname();
                        break;
                    }
                }
                if (!vtepFound) {
                    addVtepInITMConfigDS(subnetList, subnetMaskObj, vtepList, tepIpAddress, tzName,
                        dpnId, portName, wrTx);
                } else {
                    // vtep is found, update it with tep-ip
                    vtepList.remove(oldVtep);
                    addVtepInITMConfigDS(subnetList, subnetMaskObj, vtepList, tepIpAddress, tzName,
                        dpnId, portName, wrTx);
                }
            }
        }
    }

    /**
     * Adds the TEP into Vtep list in the subnet list in the transport zone list
     * from ITM configuration Datastore by merge operation with write transaction.
     *
     * @param subnetList subnets list object
     * @param subnetMaskObj subnet mask in IpPrefix object
     * @param updatedVtepList updated Vteps list object which will have new TEP for addition
     * @param tepIpAddress TEP IP address in IpAddress object
     * @param tzName transport zone name in string
     * @param dpnId DPN datapath ID in BigInteger
     * @param portName port name as a part of VtepsKey
     * @param wrTx WriteTransaction object
     */
    public static void addVtepInITMConfigDS(List<Subnets> subnetList, IpPrefix subnetMaskObj,
        List<Vteps> updatedVtepList, IpAddress tepIpAddress, String tzName, BigInteger dpnId,
        String portName, WriteTransaction wrTx) {
        //Create TZ node path
        InstanceIdentifier<TransportZone> tZonepath =
            InstanceIdentifier.builder(TransportZones.class)
                .child(TransportZone.class, new TransportZoneKey(tzName)).build();

        // this check is needed to reuse same function from TransportZoneListener
        // when VTEP is moved from TepsNotHosted list to TZ configured from Northbound.
        if (dpnId.compareTo(BigInteger.ZERO) > 0) {
            // create vtep
            VtepsKey vtepkey = new VtepsKey(dpnId, portName);
            Vteps vtepObj =
                new VtepsBuilder().setDpnId(dpnId).setIpAddress(tepIpAddress).setKey(vtepkey).setPortname(portName).build();

            // Add vtep obtained from DPN into list
            updatedVtepList.add(vtepObj);
        }

        // Create subnet object
        SubnetsKey subKey = new SubnetsKey(subnetMaskObj);
        IpAddress gatewayIP = new IpAddress(ITMConstants.DUMMY_GATEWAY_IP.toCharArray());
        int vlanID = ITMConstants.DUMMY_VLANID;

        Subnets subnet =
            new SubnetsBuilder().setGatewayIp(gatewayIP)
                .setKey(subKey).setPrefix(subnetMaskObj)
                .setVlanId(vlanID).setVteps(updatedVtepList).build();

        // add subnet into subnet list
        subnetList.add(subnet);

        // create TZ node with updated subnet having new vtep
        TransportZone updatedTzone =
            new TransportZoneBuilder().setKey(new TransportZoneKey(tzName)).setSubnets(subnetList)
                .setZoneName(tzName).build();

        LOG.trace("Adding TEP (TZ: {} Subnet: {} TEP IP: {} DPN-ID: {}) in ITM Config DS.", tzName,
            subnetMaskObj.getValue().toString(), tepIpAddress, dpnId);
        // Update TZ in Config DS to add vtep in TZ
        wrTx.merge(LogicalDatastoreType.CONFIGURATION, tZonepath, updatedTzone, true);
    }

    /**
     * Adds the TEP into Vtep list in the subnet list in the transport zone list
     * from ITM configuration Datastore by merge operation with write transaction.
     *
     * @param tzName transport zone name in string
     * @param tepIpAddress TEP IP address in IpAddress object
     * @param dpnId DPN datapath ID in BigInteger
     * @param dataBroker data broker handle to perform operations on config datastore
     * @param wrTx WriteTransaction object
     */
    protected static void addUnknownTzTepIntoTepsNotHosted(String tzName, IpAddress tepIpAddress,
        BigInteger dpnId, DataBroker dataBroker, WriteTransaction wrTx) {
        List<UnknownVteps> vtepList = null;

        TepsNotHostedInTransportZone unknownTz =
            ItmUtils.getUnknownTransportZoneFromITMConfigDS(tzName, dataBroker);
        if (unknownTz == null) {
            LOG.trace("Unhosted TransportZone does not exist.");
            vtepList = new ArrayList<UnknownVteps>();
            addVtepIntoTepsNotHosted(vtepList, tepIpAddress, tzName, dpnId, wrTx);
        } else {
            vtepList = unknownTz.getUnknownVteps();
            if (vtepList == null || vtepList.isEmpty()) {
                //  case: vtep list does not exist or it has no elements
                if (vtepList == null) {
                    vtepList = new ArrayList<UnknownVteps>();
                }
                LOG.trace("Add TEP in unhosted TZ ({}) when no vtep-list in the TZ.", tzName);
                addVtepIntoTepsNotHosted(vtepList, tepIpAddress, tzName, dpnId, wrTx);
            } else {
                //  case: vtep list has elements
                boolean vtepFound = false;
                UnknownVteps oldVtep = null;

                for (UnknownVteps vtep : vtepList) {
                    if (vtep.getDpnId().equals(dpnId)) {
                        vtepFound = true;
                        oldVtep = vtep;
                        break;
                    }
                }
                if (!vtepFound) {
                    addVtepIntoTepsNotHosted(vtepList, tepIpAddress, tzName, dpnId,
                        wrTx);
                } else {
                    // vtep is found, update it with tep-ip
                    vtepList.remove(oldVtep);
                    addVtepIntoTepsNotHosted(vtepList, tepIpAddress, tzName, dpnId,
                        wrTx);
                }
            }
        }
    }

    /**
     * Adds the TEP into Unknown Vtep list under the transport zone in the TepsNotHosted list
     * from ITM configuration Datastore by merge operation with write transaction.
     *
     * @param updatedVtepList updated UnknownVteps list object which will have new TEP for addition
     *                        into TepsNotHosted
     * @param tepIpAddress TEP IP address in IpAddress object
     * @param tzName transport zone name in string
     * @param dpnId DPN datapath ID in BigInteger
     * @param wrTx WriteTransaction object
     */
    protected static void addVtepIntoTepsNotHosted(List<UnknownVteps> updatedVtepList,
        IpAddress tepIpAddress, String tzName, BigInteger dpnId, WriteTransaction wrTx) {
        //Create TZ node path
        InstanceIdentifier<TepsNotHostedInTransportZone> tZonepath =
            InstanceIdentifier.builder(TransportZones.class)
                .child(TepsNotHostedInTransportZone.class,
                    new TepsNotHostedInTransportZoneKey(tzName)).build();

        // create vtep
        UnknownVtepsKey vtepkey = new UnknownVtepsKey(dpnId);
        UnknownVteps vtepObj =
            new UnknownVtepsBuilder().setDpnId(dpnId).setIpAddress(tepIpAddress).setKey(vtepkey)
                .build();

        // Add vtep obtained into unknown TZ tep list
        updatedVtepList.add(vtepObj);

        // create unknown TZ node with updated vtep list
        TepsNotHostedInTransportZone updatedTzone = new TepsNotHostedInTransportZoneBuilder()
            .setKey(new TepsNotHostedInTransportZoneKey(tzName)).setZoneName(tzName)
            .setUnknownVteps(updatedVtepList).build();

        LOG.trace("Adding TEP into unhosted (TZ: {}, DPID: {}, TEP IP: {}) in ITM Config DS.",
            tzName, dpnId, tepIpAddress);

        // Update TZ in Config DS.
        wrTx.merge(LogicalDatastoreType.CONFIGURATION, tZonepath, updatedTzone, true);
    }
}