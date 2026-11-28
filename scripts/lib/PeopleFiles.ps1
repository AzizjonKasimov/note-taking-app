# Reading People's files in the notes repo (life) on this machine: one JSON file per person in
# life\people\, written by Notes' People tab on the phone since 2.5 (PeopleFiles.kt there), and
# kept current on this PC by the life-pull hooks. Used by network-lookup.ps1 and queue-draft.ps1.
# Dot-source it. The files are plain text: nothing here asks for a passphrase.

function Get-LifePeopleFolder {
    <# life\people beside this checkout: E:\PersonalProjects\note_taking_app\scripts -> E:\PersonalProjects\life\people. #>
    $projects = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
    [IO.Path]::Combine($projects, 'life', 'people')
}

function Test-LifePeople {
    <# Whether the folder holds people's files, that is, whether Notes has People's data. #>
    param([Parameter(Mandatory)][string]$Folder)
    if (-not (Test-Path -LiteralPath $Folder -PathType Container)) { return $false }
    $null -ne (Get-ChildItem -LiteralPath $Folder -Filter '*.json' -File | Where-Object Name -ne 'reports.json' | Select-Object -First 1)
}

function Get-PeopleFromLife {
    <#
    Reads every person's file into one object shaped like Network App's backup content
    (people, interactions, needs, capabilities, affiliations, facts), which
    Invoke-NetworkLookup prints from. Contact values are not in the files, and the
    assistant's reports (reports.json) are not read.
    #>
    param([Parameter(Mandatory)][string]$Folder)
    $people = [System.Collections.Generic.List[object]]::new()
    $newest = [DateTime]::MinValue
    $lists = [ordered]@{}
    foreach ($name in 'interactions', 'needs', 'capabilities', 'affiliations', 'facts') {
        $lists[$name] = [System.Collections.Generic.List[object]]::new()
    }
    foreach ($file in Get-ChildItem -LiteralPath $Folder -Filter '*.json' -File) {
        if ($file.Name -eq 'reports.json') { continue }
        if ($file.LastWriteTimeUtc -gt $newest) { $newest = $file.LastWriteTimeUtc }
        $doc = Get-Content -LiteralPath $file.FullName -Raw -Encoding utf8 | ConvertFrom-Json
        if ($doc.format -ne 'notes-people-person' -or $doc.version -ne 1) {
            throw "$($file.Name) was written by a Notes that this script does not know. Update this checkout."
        }
        $people.Add($doc.person)
        foreach ($name in @($lists.Keys)) {
            foreach ($item in @($doc.$name)) { if ($null -ne $item) { $lists[$name].Add($item) } }
        }
    }
    [pscustomobject]@{
        source        = 'life'
        schemaVersion = 10
        # When a person's file last changed on this PC, for the lookup's first line.
        exportedAt    = if ($newest -gt [DateTime]::MinValue) { [DateTimeOffset]::new($newest).ToUnixTimeMilliseconds() } else { $null }
        people        = $people.ToArray()
        interactions  = $lists['interactions'].ToArray()
        needs         = $lists['needs'].ToArray()
        capabilities  = $lists['capabilities'].ToArray()
        affiliations  = $lists['affiliations'].ToArray()
        facts         = $lists['facts'].ToArray()
    }
}
