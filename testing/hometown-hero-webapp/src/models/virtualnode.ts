export interface SignerSummaryHash {
    algorithm: string;
    bytes: string;
    offset: number;
    size: number;
}

export interface CpiIdentifier {
    name: string;
    signerSummaryHash: SignerSummaryHash;
    version: string;
}

export interface HoldingIdentity {
    groupId: string;
    fullHash: string;
    shortHash: string;
    x500Name: string;
}

export interface VirtualNode {
    cpiIdentifier: CpiIdentifier;
    cryptoDdlConnectionId: string;
    cryptoDmlConnectionId: string;
    holdingIdentity: HoldingIdentity;
    hsmConnectionId: string;
    vaultDdlConnectionId: string;
    vaultDmlConnectionId: string;
    cluster: string;
}

export interface VirtualNodes {
    virtualNodes: VirtualNode[];
}
