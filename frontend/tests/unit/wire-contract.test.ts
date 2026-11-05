import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import ts from 'typescript'

interface WireContract {
  endpoints: Record<string, {
    requiredFields?: string[]
    optionalFields?: string[]
    userFields?: string[]
    frontendType?: string
  }>
}

const contractPath = path.resolve(
  process.cwd(),
  '..',
  'contracts',
  'api-wire-contract.json'
)
const typesPath = path.resolve(process.cwd(), 'src', 'types', 'index.ts')

const contract: WireContract = JSON.parse(
  fs.readFileSync(contractPath, 'utf8')
) as WireContract

function interfaceProperties(source: string): Map<string, Set<string>> {
  const sourceFile = ts.createSourceFile(
    'index.ts',
    source,
    ts.ScriptTarget.Latest,
    true
  )
  const interfaces = new Map<string, Set<string>>()
  sourceFile.forEachChild(node => {
    if (!ts.isInterfaceDeclaration(node)) {
      return
    }
    const properties = new Set<string>()
    node.members.forEach(member => {
      if (ts.isPropertySignature(member) && member.name) {
        properties.add(member.name.getText(sourceFile))
      }
    })
    interfaces.set(node.name.text, properties)
  })
  return interfaces
}

describe('frontend types match the backend wire contract', () => {
  const interfaces = interfaceProperties(fs.readFileSync(typesPath, 'utf8'))

  for (const [endpoint, shape] of Object.entries(contract.endpoints)) {
    if (!shape.frontendType) {
      continue
    }

    it(`${endpoint} fields exist in ${shape.frontendType}`, () => {
      const properties = interfaces.get(shape.frontendType as string)
      expect(properties, `${shape.frontendType} interface is missing`).toBeDefined()

      const wireFields = [
        ...(shape.requiredFields ?? []),
        ...(shape.optionalFields ?? []),
      ]
      expect(wireFields.length).toBeGreaterThan(0)
      for (const field of wireFields) {
        expect(
          properties?.has(field),
          `${shape.frontendType} must declare wire field ${field}`
        ).toBe(true)
      }
    })
  }

  it('login user object fields exist in the frontend User type', () => {
    const userFields = contract.endpoints['POST /api/auth/login'].userFields ?? []
    expect(userFields.length).toBeGreaterThan(0)
    const user = interfaces.get('User')
    expect(user).toBeDefined()
    for (const field of userFields) {
      expect(
        user?.has(field),
        `User must declare login user field ${field}`
      ).toBe(true)
    }
  })
})
