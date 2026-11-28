#Requires -Version 7
<#
.SYNOPSIS
    Look people up from this PC: in the people folder of the notes repo (Notes'
    People since 2.5), or in Network App's encrypted backup until the move.

.DESCRIPTION
    Prints only what was asked for:

        -Name     each person whose name or other name matches the text
        -Search   people with a saved record that holds every word
        -Self     the owner's own profile

    A profile shows what the app's assistant may read through its tools and no
    more: a contact value never appears. A name that matches more than five people
    lists their names only, and a profile shows the latest ten notes unless
    -AllNotes. Assistant reports are not read. The script writes nothing to disk.

    Once Notes holds People, it reads the plain files in life\people\ (kept
    current by the life-pull hooks), with no passphrase. Before that, it downloads
    Network App's encrypted backup from the private data repository, and the owner
    types the passphrase for every lookup: at the prompt in a console, or, when
    the script runs without one, as from an agent's shell, in a small window that
    names what is being looked up. The passphrase is never stored, passed as an
    argument, or echoed.

.EXAMPLE
    .\scripts\network-lookup.ps1 -Name 'Synthetic Person', 'Synthetic Other'

.EXAMPLE
    .\scripts\network-lookup.ps1 -Search 'robotics hiring'

.EXAMPLE
    .\scripts\network-lookup.ps1 -Self
#>
[CmdletBinding(DefaultParameterSetName = 'Name')]
param(
    [Parameter(Mandatory, ParameterSetName = 'Name', Position = 0)]
    [string[]]$Name,
    [Parameter(Mandatory, ParameterSetName = 'Search')]
    [string]$Search,
    [Parameter(Mandatory, ParameterSetName = 'Self')]
    [switch]$Self,
    # Every note of each person shown, not only the latest ten.
    [switch]$AllNotes,
    # Let -Search find archived people too. Name lookups always include them, marked.
    [switch]$IncludeArchived,
    # The most people a search shows.
    [int]$MaxPeople = 15,
    # The notes repo's people folder; by default life\people beside this checkout.
    [string]$Folder,
    # Network App's encrypted backup, read until Notes holds People.
    [string]$Owner = 'AzizjonKasimov',
    [string]$Repo = 'network-app-data',
    [string]$Branch = 'main',
    [string]$Path = 'network-backup.enc.json',
    # Read an envelope already on this machine instead of fetching one.
    [string]$EnvelopeFile
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\NetworkBackup.ps1')
. (Join-Path $PSScriptRoot 'lib\NetworkLookup.ps1')
. (Join-Path $PSScriptRoot 'lib\PeopleFiles.ps1')

if (-not $Folder) { $Folder = Get-LifePeopleFolder }
if (-not $EnvelopeFile -and (Test-LifePeople -Folder $Folder)) {
    Write-Host "==> Reading people from $Folder" -ForegroundColor Cyan
    $payload = Get-PeopleFromLife -Folder $Folder
} else {
    $purpose = switch ($PSCmdlet.ParameterSetName) {
        'Self' { 'Look up your own profile in your network backup.' }
        'Search' { "Search your network backup for: $Search" }
        default { "Look up in your network backup: $($Name -join ', ')" }
    }
    $envelope = Get-NetworkBackupEnvelope -Owner $Owner -Repo $Repo -Branch $Branch -Path $Path -EnvelopeFile $EnvelopeFile
    $secure = Read-NetworkBackupPassphrase -Purpose $purpose
    try {
        $payload = Unprotect-NetworkBackup -Envelope $envelope -Passphrase $secure
    } finally {
        $secure.Dispose()
    }
}
Invoke-NetworkLookup -Payload $payload -Name $Name -Search $Search -Self:$Self -AllNotes:$AllNotes -IncludeArchived:$IncludeArchived -MaxPeople $MaxPeople
